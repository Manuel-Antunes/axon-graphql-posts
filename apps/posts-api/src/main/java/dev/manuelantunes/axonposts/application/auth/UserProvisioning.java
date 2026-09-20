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
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class UserProvisioning {
    private static final Logger log = LoggerFactory.getLogger(UserProvisioning.class);

    private final UserRepository users;
    private final CommandGateway commandGateway;

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

    private Optional<User> reactivate(Identity identity) {
        return users.findDeletedUserIdByAccount(identity.provider(), identity.subject())
                .map(userId -> {
                    log.info("reativando a conta apagada {} — a credencial voltou", userId);
                    commandGateway.sendAndWait(new RestoreUser(userId));
                    return reload(userId);
                });
    }

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
        commandGateway.sendAndWait(new RegisterUser(
                id, identity.email(), identity.name(), identity.author(), null, null));
        commandGateway.sendAndWait(new LinkAccount(id, identity.provider(), identity.subject()));

        log.info("usuário provisionado do Keycloak: {} ({}), autor={}",
                identity.email(), id, identity.author());
        return reload(id);
    }

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

    private User reload(UserId id) {
        return users.findById(id).orElseThrow(
                () -> new IllegalStateException("usuário " + id + " não foi salvo pelo command"));
    }
}
