package dev.manuelantunes.axonposts.application.user.view;

import dev.manuelantunes.axonposts.domain.user.Account;
import dev.manuelantunes.axonposts.domain.user.Author;
import dev.manuelantunes.axonposts.domain.user.User;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class UserViewMapper {
    private static final Comparator<Account> BY_LINK_TIME =
            Comparator.comparing(Account::linkedAt).thenComparing(account -> account.id().value());

    public UserView toView(User user) {
        return user instanceof Author author ? toView(author) : toReaderView(user);
    }

    public AuthorView toView(Author author) {
        return new AuthorView(
                author.id().value(),
                author.name().value(),
                author.email().value(),
                author.bio(),
                toAccountViews(author));
    }

    private ReaderView toReaderView(User user) {
        return new ReaderView(
                user.id().value(),
                user.name().value(),
                user.email().value(),
                toAccountViews(user));
    }

    private List<AccountView> toAccountViews(User user) {
        return user.accounts().stream()
                .sorted(BY_LINK_TIME)
                .map(account -> new AccountView(
                        account.provider(),
                        account.subject(),
                        account.hasPassword(),
                        account.linkedAt()))
                .toList();
    }
}
