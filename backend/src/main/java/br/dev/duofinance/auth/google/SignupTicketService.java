package br.dev.duofinance.auth.google;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/**
 * Short-lived signed proof of a verified Google identity, used to finish signup. Signed with the same key as access
 * tokens but carries {@code aud = google-signup}, which the access-token decoder rejects and this decoder requires, so
 * neither token type is usable as the other.
 */
@Service
public class SignupTicketService {

    public static final Duration TTL = Duration.ofMinutes(15);
    static final String AUDIENCE = "google-signup";
    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;

    public SignupTicketService(JwtEncoder encoder, KeyPair jwtKeyPair, Clock clock) {
        this.encoder = encoder;
        this.clock = clock;
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withPublicKey((RSAPublicKey) jwtKeyPair.getPublic())
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> audience =
                jwt -> jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, audience));
        this.decoder = nimbus;
    }

    public String issue(GoogleIdentity identity) {
        Instant now = Instant.now(clock);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(identity.subject())
                .claim(EMAIL_CLAIM, identity.email())
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(TTL));
        if (identity.name() != null) {
            claims.claim(NAME_CLAIM, identity.name());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    public GoogleIdentity read(String ticket) {
        Jwt jwt;
        try {
            jwt = decoder.decode(ticket);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.GOOGLE_SIGNUP_TICKET_INVALID);
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (subject == null || email == null) {
            throw new ApiException(ErrorCode.GOOGLE_SIGNUP_TICKET_INVALID);
        }
        return new GoogleIdentity(subject, email, jwt.getClaimAsString(NAME_CLAIM));
    }
}
