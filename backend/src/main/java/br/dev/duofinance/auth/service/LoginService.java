package br.dev.duofinance.auth.service;

import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.model.IdentityProvider;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserIdentity;
import br.dev.duofinance.user.repository.UserIdentityRepository;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Password login. Every failure is the same generic 401; repeated failures lock the account temporarily. */
@Service
public class LoginService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UserIdentityRepository identities;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessions;
    private final Clock clock;
    /** Verified against when the e-mail is unknown or the account is locked, so response time stays comparable. */
    private final String dummyHash;

    public LoginService(
            UserIdentityRepository identities,
            UserRepository users,
            PasswordEncoder passwordEncoder,
            AuthSessionService sessions,
            Clock clock) {
        this.identities = identities;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("duo-finance-timing-equalizer");
    }

    /**
     * {@code noRollbackFor}: the failure counter and lock must be committed even though the request ends in a 401.
     * The user row is locked so concurrent failures cannot lose increments.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SessionTokens login(String rawEmail, String password) {
        String email = SecureTokens.normalizeEmail(rawEmail);
        Optional<UserIdentity> identity = identities.findByProviderAndProviderSubject(IdentityProvider.LOCAL, email);
        Optional<User> locked = identity.flatMap(i -> users.findByIdForUpdate(i.getUserId()));
        if (locked.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw invalid();
        }

        User user = locked.get();
        Instant now = Instant.now(clock);
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            passwordEncoder.matches(password, dummyHash);
            throw invalid();
        }

        String hash = identity.get().getPasswordHash();
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            recordFailure(user, now);
            throw invalid();
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        return sessions.start(user);
    }

    private void recordFailure(User user, Instant now) {
        int failures = user.getFailedLoginCount() + 1;
        if (failures >= MAX_FAILURES) {
            user.setLockedUntil(now.plus(LOCK_DURATION));
            user.setFailedLoginCount(0);
        } else {
            user.setFailedLoginCount(failures);
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
}
