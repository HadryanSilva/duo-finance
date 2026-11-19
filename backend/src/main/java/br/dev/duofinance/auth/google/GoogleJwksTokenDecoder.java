package br.dev.duofinance.auth.google;

import java.time.Clock;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Production {@link GoogleTokenDecoder}: Google's published JWKS plus a timestamp check on the application clock. The
 * JWKS fetch has its own connect and read timeouts so a slow Google endpoint cannot hold request threads.
 */
@Component
class GoogleJwksTokenDecoder implements GoogleTokenDecoder {

    static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    static final Duration JWKS_TIMEOUT = Duration.ofSeconds(5);

    private final NimbusJwtDecoder delegate;

    GoogleJwksTokenDecoder(Clock clock) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(JWKS_TIMEOUT);
        requests.setReadTimeout(JWKS_TIMEOUT);
        this.delegate = NimbusJwtDecoder.withJwkSetUri(JWKS_URI)
                .restOperations(new RestTemplate(requests))
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamps.setClock(clock);
        this.delegate.setJwtValidator(timestamps);
    }

    @Override
    public Jwt decode(String idToken) {
        return delegate.decode(idToken);
    }
}
