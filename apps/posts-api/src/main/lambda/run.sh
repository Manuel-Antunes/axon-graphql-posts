#!/bin/sh
#
# O ponto de entrada da função quando o transporte é o AWS Lambda Web Adapter.
#
# O `handler` da função é o NOME DESTE ARQUIVO (`run.sh`), e não uma classe Java: com
# `AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap` quem assume a partida é a layer do adapter, que executa
# este script, espera o health check responder e só então começa a encaminhar invocações.
#
# `exec` e não uma chamada comum: o processo do Java precisa HERDAR o PID 1 do sandbox. Sem isso o
# shell fica entre o Lambda e a JVM, e o sinal de shutdown não chega a quem precisa dele.
set -eu

# A porta é do adapter, não nossa: ele escolhe para onde encaminhar e nos diz por `AWS_LWA_PORT`.
PORT="${AWS_LWA_PORT:-8080}"

# ==== O BINÁRIO NATIVO, quando o zip o trouxe ====================================================
#
# O mesmo script serve os DOIS empacotamentos, e quem decide é o conteúdo do zip — não uma variável,
# não um segundo arquivo. O alvo `lambda-stream:native` põe `application` na raiz; o `:jvm` põe
# `quarkus-app/`. Um `if` é toda a diferença, e é o que permite trocar de artefato sem tocar na
# infraestrutura: o `handler` da função continua sendo `run.sh`.
#
# Por que o binário ainda recebe `-Dquarkus.http.*`: um executável nativo do Quarkus aceita as mesmas
# propriedades de RUNTIME que o JAR. O que ele não aceita é configuração de build time, que já está
# compilada dentro dele.
if [ -x /var/task/application ]; then
    exec /var/task/application \
        -Dquarkus.http.host=0.0.0.0 \
        -Dquarkus.http.port="$PORT"
fi

# ==== O caminho da JVM ===========================================================================
#
# `UseSerialGC` porque o sandbox tem pouca CPU e o G1 gasta thread com o que aqui não rende. NÃO há
# `TieredStopAtLevel=1`: ele encurta o cold start e estraga o resto, e esta função não é um disparo
# curto — ela serve GraphQL e pode segurar uma subscription por minutos, tempo em que o C2 paga.
exec java \
    -Dquarkus.http.host=0.0.0.0 \
    -Dquarkus.http.port="$PORT" \
    -XX:+UseSerialGC \
    -Djava.security.egd=file:/dev/urandom \
    -jar /var/task/quarkus-app/quarkus-run.jar
