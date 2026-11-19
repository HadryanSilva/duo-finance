package br.dev.duofinance.auth.google;

import java.time.Clock;
import java.time.Duration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Production {@link GoogleTokenDecoder}: Google's published JWKS plus a timestamp check on the application clock. */
@Component
class GoogleJwksTokenDecoder implements GoogleTokenDecoder {

    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    private final NimbusJwtDecoder delegate;

    GoogleJwksTokenDecoder(Clock clock) {
        this.delegate = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamps.setClock(clock);
        this.delegate.setJwtValidator(timestamps);
    }

    @Override
    public Jwt decode(String idToken) {
        return delegate.decode(idToken);
    }
}
