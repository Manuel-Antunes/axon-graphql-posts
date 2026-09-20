package dev.manuelantunes.axonposts.domain.user;

import java.util.Locale;

public enum Role {
    USER,

    AUTHOR;

    public String claim() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static final String AUTHOR_CLAIM = "author";

    public static final String USER_CLAIM = "user";
}
