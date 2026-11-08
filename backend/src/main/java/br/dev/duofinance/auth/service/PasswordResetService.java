package br.dev.duofinance.auth.service;

import br.dev.duofinance.auth.model.UserToken;
import br.dev.duofinance.auth.model.UserTokenType;
import br.dev.duofinance.auth.repository.UserTokenRepository;
import br.dev.duofinance.shared.config.AppProperties;
import br.dev.duofinance.shared.email.EmailRequestedEvent;
import br.dev.duofinance.shared.email.EmailTemplate;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

    static final Duration TTL = Duration.ofHours(1);

    private final UserTokenRepository tokens;
    private final UserIdentityRepository identities;
    private final UserRepository users;
    private final AuthSessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    public PasswordResetService(
            UserTokenRepository tokens,
            UserIdentityRepository identities,
            UserRepository users,
            AuthSessionService sessions,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            AppProperties props,
            Clock clock) {
        this.tokens = tokens;
        this.identities = identities;
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /** Silent for unknown e-mails and accounts without a password identity, so callers cannot probe for users. */
    @Transactional
    public void requestReset(String rawEmail) {
        String email = SecureTokens.normalizeEmail(rawEmail);
        identities
                .findByProviderAndProviderSubject(IdentityProvider.LOCAL, email)
                .flatMap(identity -> users.findById(identity.getUserId()))
                .ifPresent(this::sendReset);
    }

    private void sendReset(User user) {
        Instant now = Instant.now(clock);
        tokens.invalidateUnused(user.getId(), UserTokenType.PASSWORD_RESET, now);
        String raw = SecureTokens.generate();
        tokens.save(new UserToken(
                user.getId(), UserTokenType.PASSWORD_RESET, SecureTokens.sha256Hex(raw), now.plus(TTL), now));
        String base = props.publicBaseUrl().toString().replaceAll("/+$", "");
        events.publishEvent(new EmailRequestedEvent(
                user.getEmail(),
                EmailTemplate.RESET_PASSWORD,
                user.getLocale(),
                Map.of("name", user.getDisplayName(), "link", base + "/reset-password?token=" + raw)));
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        Instant now = Instant.now(clock);
        String hash = SecureTokens.sha256Hex(rawToken);
        if (tokens.consume(hash, UserTokenType.PASSWORD_RESET, now) == 0) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        UUID userId = tokens.findByTokenHashAndType(hash, UserTokenType.PASSWORD_RESET)
                .orElseThrow()
                .getUserId();
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        UserIdentity identity = identities
                .findByUserIdAndProvider(userId, IdentityProvider.LOCAL)
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        identity.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);
        sessions.revokeAll(userId);
    }
}
