package br.dev.duofinance.auth.google;

import br.dev.duofinance.shared.config.AppProperties;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.token.SecureTokens;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdTokenVerifier {

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final GoogleTokenDecoder decoder;
    private final String clientId;

    @Autowired
    public GoogleIdTokenVerifier(GoogleTokenDecoder decoder, AppProperties props) {
        this(decoder, props.auth() == null ? null : props.auth().googleClientId());
    }

    GoogleIdTokenVerifier(GoogleTokenDecoder decoder, String clientId) {
        this.decoder = decoder;
        this.clientId = clientId == null || clientId.isBlank() ? null : clientId;
    }

    public boolean enabled() {
        return clientId != null;
    }

    public GoogleIdentity verify(String idToken) {
        if (!enabled()) {
            throw new ApiException(ErrorCode.GOOGLE_LOGIN_DISABLED);
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID);
        }
        List<String> audience = jwt.getAudience();
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || !ISSUERS.contains(issuer) || audience == null || !audience.contains(clientId)) {
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID);
        }
        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (subject == null || email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.GOOGLE_TOKEN_INVALID);
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw new ApiException(ErrorCode.GOOGLE_EMAIL_UNVERIFIED);
        }
        return new GoogleIdentity(subject, SecureTokens.normalizeEmail(email), jwt.getClaimAsString("name"));
    }
}
