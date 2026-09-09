package dev.manuelantunes.axonposts.application.auth;

import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import dev.manuelantunes.axonposts.domain.user.UserRepository;
import dev.manuelantunes.axonposts.domain.user.vo.Email;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Traz para o banco local o usuário que o Keycloak acabou de autenticar — <i>just-in-time provisioning</i>.
 *
 * <h2>Por que a aplicação continua tendo uma tabela de usuários</h2>
 * Porque o Keycloak sabe quem a pessoa é, não o que ela é <b>aqui</b>. Os posts têm chave estrangeira
 * para {@code users}; o autor é uma linha em {@code authors}; a exclusão lógica é nossa. Nada disso cabe
 * no broker, e depender dele em toda leitura transformaria "listar posts" numa chamada de rede.
 * <p>
 * A divisão é: o Keycloak é dono da <b>credencial</b>, nós somos donos do <b>perfil</b>. A tabela
 * {@code accounts} é a costura entre os dois.
 *
 * <h2>Os três caminhos, em ordem</h2>
 * <ol>
 *   <li><b>conta conhecida</b> — {@code (provider, sub)} já existe: é só devolver o dono. É o caminho de
 *       99,9% das requisições;</li>
 *   <li><b>account linking</b> — a conta é nova, mas o e-mail já é de alguém: é a <b>mesma pessoa</b>
 *       chegando por um provedor diferente. Liga-se a credencial ao usuário existente, e ele mantém id,
 *       posts e histórico. Sem este passo, entrar pelo Google depois de ter entrado pelo Keycloak criaria
 *       um segundo usuário e os posts do primeiro sumiriam de vista;</li>
 *   <li><b>usuário novo</b> — cria-se o perfil e liga-se a primeira conta.</li>
 * </ol>
 *
 * <h2>O casamento por e-mail é seguro aqui, e não é sempre</h2>
 * Ligar contas por e-mail só vale porque <b>o Keycloak é o único emissor</b> e ele verifica o e-mail
 * antes. Num cenário com vários provedores diretos, um deles poderia afirmar um e-mail que não é do
 * usuário e sequestrar a conta local — por isso este casamento tem de exigir e-mail verificado, e por
 * isso o Keycloak fazer a federação por dentro é o desenho mais seguro.
 */
@Service
public class UserProvisioning {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioning.class);

    private final UserRepository users;
    private final Clock clock;

    public UserProvisioning(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public User provision(Identity identity) {
        return users.findByAccount(identity.provider(), identity.subject())
                .map(known -> promoteIfNeeded(known, identity))
                .orElseGet(() -> linkOrCreate(identity));
    }

    private User linkOrCreate(Identity identity) {
        Instant now = clock.instant();

        return users.findByEmail(Email.of(identity.email()))
                .map(existing -> {
                    log.info("account linking: {} de {} ligada ao usuário {}",
                            identity.subject(), identity.provider(), existing.id());
                    existing.link(identity.provider(), identity.subject(), now);
                    users.save(existing);
                    return promoteIfNeeded(existing, identity);
                })
                .orElseGet(() -> create(identity, now));
    }

    private User create(Identity identity, Instant now) {
        UserId id = UserId.newId();

        // o tipo concreto é decidido pela role do token: é a única chance de decidir sem um INSERT extra
        User user = identity.author()
                ? Author.register(id, identity.email(), identity.name(), "", now)
                : User.register(id, identity.email(), identity.name(), now);

        user.link(identity.provider(), identity.subject(), now);
        users.save(user);

        log.info("usuário provisionado do Keycloak: {} ({}), autor={}",
                identity.email(), id, identity.author());
        return user;
    }

    /**
     * O caso que a herança por subclasse torna trabalhoso: a role {@code author} pode ser concedida no
     * Keycloak <b>depois</b> de o usuário já existir aqui como {@code User} comum. Como o tipo de uma
     * linha em {@code JOINED} é a existência da linha filha, promover é inserir essa linha.
     * <p>
     * O caminho inverso — perder a role — <b>não</b> apaga a linha de {@code authors} de propósito: os
     * posts continuam apontando para ela, e a autoria de quem já escreveu não deixa de ser verdade porque
     * a permissão mudou. Quem decide se ele ainda pode escrever é o {@code @PreAuthorize}, a cada
     * requisição, pela role do token.
     */
    private User promoteIfNeeded(User user, Identity identity) {
        if (!identity.author() || user.isAuthor()) {
            return user;
        }

        log.info("promovendo {} a autor: a role veio no token e a linha em authors não existia", user.id());
        users.promoteToAuthor(user.id(), "");

        // relê para obter a instância do tipo certo — ver o javadoc de promoteToAuthor
        return users.findById(user.id()).orElse(user);
    }
}
