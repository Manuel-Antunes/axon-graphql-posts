#!/usr/bin/env bash
#
# O AMBIENTE de um build do Quarkus — e nada além dele.
#
#   infra/scripts/build-env.sh ./mvnw package -DskipTests -Plambda-http -Dnative -Pnative-container
#
# Quem o invoca são os alvos `lambda-*` do Nx (`apps/*/project.json`), e ele não sabe o que está
# sendo construído: garante um JDK serviçável, avisa quando o Docker não tem memória para um build
# nativo em container, e faz `exec`. COMO empacotar é decisão dos alvos; o que este arquivo resolve
# é o que quebraria qualquer um deles antes do primeiro módulo compilar.
#
# Por que existe em vez de uma linha de `env` no `project.json`: as duas checagens são DINÂMICAS —
# uma procura um JDK instalado, a outra pergunta ao Docker. E as duas deixaram de ser conveniência
# quando o build passou a ser disparado pelo `sst deploy`, onde quem escolhe o ambiente é o CLI e
# não uma linha de comando que alguém prefixa com `JAVA_HOME=`.
set -euo pipefail

cd "$(dirname "$0")/../.."

# ==== O JDK ======================================================================================
#
# O compilador precisa saber fazer `release 21`. Num shell com JAVA_HOME apontando para um JDK 17 a
# falha é `error: release version 21 not supported`, no PRIMEIRO módulo — longe o bastante do fim
# para parecer outra coisa. Este bloco a transforma numa frase.
JAVA_MAJOR=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')

# E se o shell trouxer um JDK velho, PROCURA um serviçável em vez de desistir.
if [ "${JAVA_MAJOR:-0}" -lt 21 ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  for v in 25 24 23 22 21; do
    if CANDIDATE=$(/usr/libexec/java_home -v "$v" 2>/dev/null); then
      echo "==> JAVA_HOME do shell tem JDK ${JAVA_MAJOR:-?}; usando o $v de $CANDIDATE"
      export JAVA_HOME="$CANDIDATE"
      JAVA_MAJOR=$v
      break
    fi
  done
fi

if [ "${JAVA_MAJOR:-0}" -lt 21 ]; then
  echo "ERRO: este build precisa de um JDK 21 ou mais novo; achei ${JAVA_MAJOR}." >&2
  echo "      JAVA_HOME=${JAVA_HOME:-<não definido>}" >&2
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    echo "      Instalados:" >&2
    /usr/libexec/java_home -V 2>&1 | sed 's/^/        /' >&2
  fi
  exit 1
fi

# NOTA sobre a JVM 25: o Quarkus 3.39 não a suporta, e o CLAUDE.md registra o augmentation do
# posts-api falhando de forma INTERMITENTE nela. Se um empacotamento falhar com "Producer method
# return type not found in index" ou "Could not load class with name", repita — ou construa com um
# JDK 21.

# ==== O QUE CADA MODO NATIVO PRECISA ==============================================================
#
# São dois caminhos, e o que falta em cada um falha longe da causa. Por isso as duas checagens são
# feitas ANTES do primeiro módulo compilar, e não depois de um minuto de reator.
case " $* " in
  *native-container*|*container-build=true*)
    # DENTRO do builder image do Mandrel. MEDIDO nesta máquina: com 7,75 GiB o build morre com
    # `exit 137` no `[1/8] Initializing`, mesmo baixando o `-Xmx`. Precisa de ~12 GiB, e o 137 não
    # menciona memória em lugar nenhum. `docker info` custa ~1s, então só é consultado aqui.
    DOCKER_BYTES=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)
    if [ "${DOCKER_BYTES:-0}" -lt 12000000000 ]; then
      echo "AVISO: o Docker tem $(echo "$DOCKER_BYTES" | awk '{printf "%.1f", $1/1073741824}') GiB." >&2
      echo "       O build nativo em container precisa de ~12 GiB e morre com exit 137 abaixo disso." >&2
      echo "       Docker Desktop -> Settings -> Resources -> Memory." >&2
    fi
    ;;
  *-Dnative*)
    # A GraalVM DA MÁQUINA — a configuração `native`, que é o default dos alvos. Sem `native-image`
    # o Quarkus só reclama depois de compilar o reator inteiro; aqui a falha custa nada e diz o que
    # fazer. O Quarkus procura nos três lugares abaixo, e esta checagem é a mesma busca.
    if ! command -v native-image >/dev/null 2>&1 \
       && [ ! -x "${GRAALVM_HOME:-/nao-existe}/bin/native-image" ] \
       && [ ! -x "${JAVA_HOME:-/nao-existe}/bin/native-image" ]; then
      echo "ERRO: build nativo pedido e nenhum 'native-image' encontrado." >&2
      echo "      Procurei no PATH, em GRAALVM_HOME=${GRAALVM_HOME:-<não definido>} e em" >&2
      echo "      JAVA_HOME=${JAVA_HOME:-<não definido>}." >&2
      echo "      Saídas: instalar a GraalVM (\`sdk install java 21-graal\`, e GRAALVM_HOME apontando" >&2
      echo "      para ela) ou usar a configuração 'native-container', que compila no builder image" >&2
      echo "      do Mandrel e só precisa de Docker." >&2
      echo "      LEMBRE: num Mac o binário local é Mach-O e NÃO roda no Lambda, que quer ELF/Linux." >&2
      exit 1
    fi

    # O MAC COM O XCODE QUEBRADO — e ele quebra o build nativo local de um jeito que não o nomeia.
    #
    # MEDIDO nesta máquina: `xcode-select -p` aponta para o Xcode.app e o `cc` que vem dali nem
    # carrega (`dlopen(@rpath/libxcodebuildLoader.dylib): Symbol not found: _XPCTypeBool`, exit 72).
    # O native-image morre em ~20s com "Unable to detect supported DARWIN native software
    # development toolchain", sem uma palavra sobre Xcode.
    #
    # Os Command Line Tools são uma instalação INDEPENDENTE, e funcionam. Duas coisas os põem no
    # jogo, e as duas são necessárias:
    #   - o PATH, porque é dali que o native-image tira o `cc`;
    #   - o `-isysroot`, porque o native-image NÃO repassa SDKROOT nem DEVELOPER_DIR ao compilador.
    # É a receita do perfil `native-clt-toolchain` de `apps/posts-api/pom.xml`, aplicada aqui porque
    # aquele perfil não existe no `apps/tagging` e porque PATH não cabe num pom.
    #
    # A troca só acontece quando o `cc` do sistema está QUEBRADO: num Mac com Xcode saudável, e em
    # qualquer Linux, este bloco não faz nada.
    CLT=/Library/Developer/CommandLineTools
    if [ "$(uname -s)" = "Darwin" ] \
       && ! cc --version >/dev/null 2>&1 \
       && [ -x "$CLT/usr/bin/cc" ] \
       && [ -d "$CLT/SDKs/MacOSX.sdk" ]; then
      echo "==> o cc do sistema não responde; usando a toolchain dos Command Line Tools"
      export PATH="$CLT/usr/bin:$PATH"
      : "${QUARKUS_NATIVE_ADDITIONAL_BUILD_ARGS:=-H:CCompilerOption=-isysroot,-H:CCompilerOption=$CLT/SDKs/MacOSX.sdk}"
      export QUARKUS_NATIVE_ADDITIONAL_BUILD_ARGS
    fi
    ;;
esac

# `exec` e não uma chamada comum: o Maven herda o PID deste processo, então um Ctrl-C do Nx chega a
# quem está construindo de verdade.
exec "$@"
