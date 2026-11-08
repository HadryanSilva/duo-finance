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
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    static final Duration TTL = Duration.ofHours(24);

    private final UserTokenRepository tokens;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    public EmailVerificationService(
            UserTokenRepository tokens,
            UserRepository users,
            ApplicationEventPublisher events,
            AppProperties props,
            Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /** Spends the token atomically, then marks the e-mail verified (a no-op if it already was). */
    @Transactional
    public void verify(String rawToken) {
        Instant now = Instant.now(clock);
        String hash = SecureTokens.sha256Hex(rawToken);
        if (tokens.consume(hash, UserTokenType.EMAIL_VERIFICATION, now) == 0) {
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
        UUID userId = tokens.findByTokenHashAndType(hash, UserTokenType.EMAIL_VERIFICATION)
                .orElseThrow()
                .getUserId();
        users.findByIdForUpdate(userId).ifPresent(user -> {
            if (user.getEmailVerifiedAt() == null) {
                user.setEmailVerifiedAt(now);
                user.setUpdatedAt(now);
            }
        });
    }

    /** Replaces any pending verification link with a new one; does nothing for verified users. */
    @Transactional
    public void resend(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        if (user.getEmailVerifiedAt() == null) {
            sendVerification(user);
        }
    }

    /** Must run inside the caller's transaction: the e-mail is only delivered after it commits. */
    @Transactional
    public void sendVerification(User user) {
        Instant now = Instant.now(clock);
        tokens.invalidateUnused(user.getId(), UserTokenType.EMAIL_VERIFICATION, now);
        String raw = SecureTokens.generate();
        tokens.save(new UserToken(
                user.getId(), UserTokenType.EMAIL_VERIFICATION, SecureTokens.sha256Hex(raw), now.plus(TTL), now));
        String base = props.publicBaseUrl().toString().replaceAll("/+$", "");
        events.publishEvent(new EmailRequestedEvent(
                user.getEmail(),
                EmailTemplate.VERIFY_EMAIL,
                user.getLocale(),
                Map.of("name", user.getDisplayName(), "link", base + "/verify-email?token=" + raw)));
    }
}
