package br.dev.duofinance.support;

import br.dev.duofinance.auth.google.GoogleTokenDecoder;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Replaces the Google JWKS decoder with one backed by a locally generated RSA key, and mints matching ID tokens. Not
 * a {@code JwtDecoder} bean, so the Resource Server is unaffected.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestGoogleTokens {

    public static final String CLIENT_ID = "test-client-id";

    private final RSAKey key = generateKey();
    private final Clock clock;
    private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));

    public TestGoogleTokens(Clock clock) {
        this.clock = clock;
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-google").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    @Primary
    GoogleTokenDecoder testGoogleTokenDecoder() throws JOSEException {
        NimbusJwtDecoder delegate =
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
        timestamps.setClock(clock);
        delegate.setJwtValidator(timestamps);
        return delegate::decode;
    }

    public String idToken(String sub, String email, boolean emailVerified) {
        return idToken(Map.of("sub", sub, "email", email, "email_verified", emailVerified));
    }

    /** A valid token with the given claims overriding the defaults; a null value removes the claim. */
    public String idToken(Map<String, Object> overrides) {
        Instant now = clock.instant();
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", List.of(CLIENT_ID));
        claims.put("name", "Ana Google");
        claims.put("iat", now);
        claims.put("exp", now.plus(Duration.ofMinutes(5)));
        claims.putAll(overrides);
        claims.values().removeIf(Objects::isNull);
        JwtClaimsSet set = JwtClaimsSet.builder().claims(c -> c.putAll(claims)).build();
        JwsHeader header =
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, set)).getTokenValue();
    }
}
