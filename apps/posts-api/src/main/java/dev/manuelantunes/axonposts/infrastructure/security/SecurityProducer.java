package dev.manuelantunes.axonposts.infrastructure.security;

import dev.manuelantunes.axonposts.domain.user.PasswordVerifier;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class SecurityProducer {
    @Produces
    @Singleton
    public PasswordVerifier passwordVerifier() {
        return BcryptUtil::matches;
    }
}
