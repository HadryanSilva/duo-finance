package br.dev.duofinance.auth.service;

import br.dev.duofinance.auth.model.UserToken;
import br.dev.duofinance.auth.model.UserTokenType;
import br.dev.duofinance.auth.repository.UserTokenRepository;
import br.dev.duofinance.shared.config.AppProperties;
import br.dev.duofinance.shared.email.EmailRequestedEvent;
import br.dev.duofinance.shared.email.EmailTemplate;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.user.model.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    static final Duration TTL = Duration.ofHours(24);

    private final UserTokenRepository tokens;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    public EmailVerificationService(
            UserTokenRepository tokens, ApplicationEventPublisher events, AppProperties props, Clock clock) {
        this.tokens = tokens;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /** Must run inside the caller's transaction: the e-mail is only delivered after it commits. */
    @Transactional
    public void sendVerification(User user) {
        Instant now = Instant.now(clock);
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
