package br.dev.duofinance.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.auth.model.RefreshToken;
import br.dev.duofinance.auth.model.UserToken;
import br.dev.duofinance.auth.model.UserTokenType;
import br.dev.duofinance.auth.repository.RefreshTokenRepository;
import br.dev.duofinance.auth.repository.UserTokenRepository;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class UserRepositoryTest {

    @Autowired
    UserRepository users;

    @Autowired
    UserIdentityRepository identities;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    UserTokenRepository userTokens;

    @Autowired
    Clock clock;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcTemplate jdbc;

    private User newUser(String email) {
        return new User(email, "Ana", "pt-BR", "2026-11", Instant.now(clock));
    }

    @Test
    void duplicateEmail_violatesUniqueConstraint() {
        users.saveAndFlush(newUser("ana@mail.com"));

        // RegistrationService maps only this constraint name to EMAIL_ALREADY_REGISTERED
        assertThatThrownBy(() -> users.saveAndFlush(newUser(" Ana@Mail.com ")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause()
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
                .isEqualTo("users_email_key");
    }

    @Test
    void findByEmail_findsNormalizedEmail() {
        users.saveAndFlush(newUser("ana@mail.com"));

        assertThat(users.findByEmail("ana@mail.com")).isPresent();
    }

    @Test
    void deletingUser_cascadesIdentitiesAndTokens() {
        Instant now = Instant.now(clock);
        User user = users.saveAndFlush(newUser("ana@mail.com"));
        identities.saveAndFlush(new UserIdentity(user.getId(), IdentityProvider.LOCAL, "ana@mail.com", "hash", now));
        refreshTokens.saveAndFlush(new RefreshToken(
                user.getId(), UUID.randomUUID(), SecureTokens.sha256Hex("r"), now.plusSeconds(60), now));
        userTokens.saveAndFlush(new UserToken(
                user.getId(), UserTokenType.PASSWORD_RESET, SecureTokens.sha256Hex("u"), now.plusSeconds(60), now));

        users.deleteById(user.getId());
        users.flush();

        assertThat(identities.findAll()).isEmpty();
        assertThat(refreshTokens.findAll()).isEmpty();
        assertThat(userTokens.findAll()).isEmpty();
    }

    @Test
    void updatingProfile_doesNotOverwriteConcurrentLoginFailureColumns() {
        UUID id = users.saveAndFlush(newUser("ana@mail.com")).getId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        TransactionTemplate concurrent = new TransactionTemplate(txManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(s -> {
            User loaded = users.findById(id).orElseThrow();
            concurrent.executeWithoutResult(
                    c -> jdbc.update("UPDATE users SET failed_login_count = 3, locked_until = now() WHERE id = ?", id));
            loaded.setDisplayName("Ana Maria");
        });

        User stored = users.findById(id).orElseThrow();
        assertThat(stored.getDisplayName()).isEqualTo("Ana Maria");
        assertThat(stored.getFailedLoginCount()).isEqualTo(3);
        assertThat(stored.getLockedUntil()).isNotNull();
    }
}
