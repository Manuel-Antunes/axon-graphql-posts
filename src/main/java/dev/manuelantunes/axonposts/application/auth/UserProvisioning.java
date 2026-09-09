package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.application.user.command.LinkAccountCommand.LinkAccount;
import dev.manuelantunes.axonposts.application.user.command.PromoteToAuthorCommand.PromoteToAuthor;
import dev.manuelantunes.axonposts.application.user.command.RegisterUserCommand.RegisterUser;
import dev.manuelantunes.axonposts.application.user.command.RestoreUserCommand.RestoreUser;
import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Traz para o banco local o usuário que o Keycloak acabou de autenticar — <i>just-in-time provisioning</i>.
 *
 * <h2>Por que a aplicação continua tendo usuários</h2>
 * O Keycloak sabe quem a pessoa é, não o que ela é <b>aqui</b>. Os posts têm chave estrangeira para
 * {@code users}, o autor é um tipo concreto do agregado, a exclusão lógica é nossa. Nada disso cabe no
 * broker, e depender dele em toda leitura transformaria "listar posts" numa chamada de rede.
 * <p>
 * A divisão é: o Keycloak é dono da <b>credencial</b>, nós somos donos do <b>perfil</b>. A tabela
 * {@code accounts} é a costura.
 *
 * <h2>Ler pelo read model, escrever por command</h2>
 * O caminho de 99,9% das requisições é "esta conta já existe" — uma consulta, e nada mais. Só os casos que
 * <b>mudam</b> alguma coisa despacham command. Provisionar por command a cada requisição autenticada
 * carregaria um stream para não escrever nada.
 *
 * <h2>Os caminhos, em ordem</h2>
 * <ol>
 *   <li><b>conta conhecida</b> — devolve o dono;</li>
 *   <li><b>conta apagada</b> — a pessoa excluiu a conta e voltou: {@code RestoreUser} reativa em vez de
 *       criar um segundo usuário;</li>
 *   <li><b>promoção interrompida</b> — sobrou um leitor encerrado sem sucessor: conclui a sequência;</li>
 *   <li><b>account linking</b> — a conta é nova mas o e-mail já é de alguém: {@code LinkAccount} no
 *       agregado existente, que mantém id, posts e histórico;</li>
 *   <li><b>usuário novo</b> — {@code RegisterUser} e {@code LinkAccount}.</li>
 * </ol>
 * Em qualquer um deles, se o token trouxer a role de autor e o usuário local for um leitor, entra a
 * {@link #promote promoção}.
 *
 * <h2>O casamento por e-mail é seguro aqui, e não é sempre</h2>
 * Ligar contas por e-mail só vale porque <b>o Keycloak é o único emissor</b> e verifica o e-mail antes.
 * Com vários provedores diretos, um deles poderia afirmar um e-mail alheio e sequestrar a conta local.
 */
@Service
public class UserProvisioning {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioning.class);

    private final UserRepository users;
    private final CommandGateway commandGateway;

    // o gateway vem do registry de componentes do Axon, não de um @Bean: a inspeção do IDE não o vê
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public UserProvisioning(UserRepository users, CommandGateway commandGateway) {
        this.users = users;
        this.commandGateway = commandGateway;
    }

    public User provision(Identity identity) {
        User user = users.findByAccount(identity.provider(), identity.subject())
                .or(() -> reactivate(identity))
                .or(() -> resumeInterruptedPromotion(identity))
                .orElseGet(() -> linkOrCreate(identity));

        return identity.author() && !user.isAuthor() ? promote(user, identity) : user;
    }

    /**
     * A pessoa apagou a conta e voltou a entrar com a mesma credencial.
     * <p>
     * Sem isto, {@code findByAccount} não acharia nada (o {@code @SQLRestriction} esconde os apagados) e o
     * caminho seguinte criaria um <b>segundo</b> usuário — com os posts do primeiro órfãos e invisíveis.
     * Reativar é o comportamento que quase todo produto tem, e aqui sai barato porque o agregado nunca
     * deixou de existir no stream.
     */
    private Optional<User> reactivate(Identity identity) {
        return users.findDeletedUserIdByAccount(identity.provider(), identity.subject())
                .map(userId -> {
                    log.info("reativando a conta apagada {} — a credencial voltou", userId);
                    commandGateway.sendAndWait(new RestoreUser(userId));
                    return reload(userId);
                });
    }

    /**
     * Conclui uma promoção que morreu no meio.
     *
     * <h3>Como a falha se parece</h3>
     * O {@link #promote} são três unidades de trabalho. Se o {@code RegisterUser} falhar depois de o
     * {@code PromoteToAuthor} ter commitado, resta um leitor encerrado — sem credenciais, invisível para
     * {@code findByEmail} — e nenhum sucessor. A pessoa ficaria sem conta.
     *
     * <h3>Por que recuperação para a frente, e não compensação</h3>
     * O estado inconsistente é <b>detectável</b> ("existe um encerrado cujo sucessor não existe") e o
     * destino é <b>determinístico</b>: o id do sucessor já está gravado em {@code supersededBy}. Então dá
     * para terminar o que faltava em vez de desfazer o que foi feito — e desfazer seria pior, porque
     * exigiria um evento que "descancela" o encerramento, que é justamente o tipo de evento que o
     * histórico não deveria ter.
     * <p>
     * A recuperação roda no login seguinte, que é exatamente quando importa: quem tenta entrar é quem
     * ficou sem conta. Não há job, agendador nem saga — a operação já é naturalmente repetida.
     * <p>
     * As credenciais não precisam ser recuperadas do leitor (ele as soltou ao ser encerrado): o token da
     * requisição atual diz qual é.
     */
    private Optional<User> resumeInterruptedPromotion(Identity identity) {
        return users.findSupersededByEmail(Email.of(identity.email()))
                .filter(reader -> users.findById(reader.supersededBy()).isEmpty())
                .map(reader -> {
                    UserId successor = reader.supersededBy();
                    log.warn("retomando promoção interrompida de {}: o sucessor {} não chegou a existir",
                            reader.id(), successor);

                    commandGateway.sendAndWait(new RegisterUser(
                            successor, identity.email(), identity.name(), true, null, reader.id()));
                    commandGateway.sendAndWait(
                            new LinkAccount(successor, identity.provider(), identity.subject()));

                    return reload(successor);
                });
    }

    private User linkOrCreate(Identity identity) {
        Optional<User> existing = users.findByEmail(Email.of(identity.email()));

        if (existing.isPresent()) {
            User owner = existing.get();
            log.info("account linking: {} de {} ligada ao usuário {}",
                    identity.subject(), identity.provider(), owner.id());
            commandGateway.sendAndWait(new LinkAccount(owner.id(), identity.provider(), identity.subject()));
            return reload(owner.id());
        }

        UserId id = UserId.newId();
        // o tipo concreto sai da role do token e vira o payload do evento — é ele que o @EntityCreator lê
        commandGateway.sendAndWait(new RegisterUser(
                id, identity.email(), identity.name(), identity.author(), null, null));
        commandGateway.sendAndWait(new LinkAccount(id, identity.provider(), identity.subject()));

        log.info("usuário provisionado do Keycloak: {} ({}), autor={}",
                identity.email(), id, identity.author());
        return reload(id);
    }

    /**
     * A promoção, em três commands.
     *
     * <h3>Por que não é um só</h3>
     * O tipo de uma entidade polimórfica é fixo desde o primeiro evento. Promover é encerrar o agregado do
     * leitor e abrir o do autor — dois streams, e um command não escreve em dois agregados.
     * <p>
     * A ordem importa e não é reversível: o {@code PromoteToAuthor} solta as credenciais do leitor, e só
     * então o índice único de {@code (provider, subject)} deixa o autor novo religá-las.
     *
     * <h3>A janela entre os passos</h3>
     * São três unidades de trabalho, não uma — um command não escreve em dois agregados, e o Axon não
     * oferece transação distribuída entre streams. Se o segundo passo falhar, o estado fica pela metade.
     * <p>
     * Isso <b>não</b> fica devendo: quem conserta é {@link #resumeInterruptedPromotion}, no login
     * seguinte. A inconsistência é detectável e o destino é determinístico, então a saída é terminar o que
     * faltou em vez de compensar. O log abaixo continua existindo para que a falha apareça na hora, mesmo
     * sabendo que ela se resolve depois.
     */
    private User promote(User reader, Identity identity) {
        UserId readerId = reader.id();
        UserId authorId = UserId.newId();
        List<Account> credentials = List.copyOf(reader.accounts());

        log.info("promovendo {} a autor: a role veio no token e o agregado era um leitor", readerId);

        commandGateway.sendAndWait(new PromoteToAuthor(readerId, authorId));
        try {
            commandGateway.sendAndWait(new RegisterUser(
                    authorId, identity.email(), identity.name(), true, null, readerId));
            credentials.forEach(account -> commandGateway.sendAndWait(
                    new LinkAccount(authorId, account.provider(), account.subject())));
        } catch (RuntimeException failed) {
            log.error("promoção de {} interrompida depois de encerrar o leitor: o sucessor {} não ficou "
                    + "completo. O usuário não consegue entrar até que isto seja reconciliado.",
                    readerId, authorId, failed);
            throw failed;
        }

        return reload(authorId);
    }

    /** Relê pelo read model: é ele que devolve a instância no tipo concreto, com as contas carregadas. */
    private User reload(UserId id) {
        return users.findById(id).orElseThrow(
                () -> new IllegalStateException("usuário " + id + " não foi salvo pelo command"));
    }
}
