package br.dev.duofinance.shared.security;

import br.dev.duofinance.shared.config.AppProperties;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * RS256 signing keys for access tokens. Uses the PEM keys from {@code app.auth.*} when set; otherwise generates an
 * ephemeral key pair (tokens do not survive a restart).
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    KeyPair jwtKeyPair(AppProperties properties) {
        AppProperties.Auth auth = properties.auth();
        String privatePem = auth == null ? null : auth.jwtPrivateKey();
        String publicPem = auth == null ? null : auth.jwtPublicKey();
        boolean hasPrivate = StringUtils.hasText(privatePem);
        boolean hasPublic = StringUtils.hasText(publicPem);
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException(
                    "app.auth.jwt-private-key and app.auth.jwt-public-key must be set together");
        }
        try {
            if (!hasPrivate) {
                log.warn("app.auth.jwt-private-key/jwt-public-key not set; using an ephemeral RSA key pair");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(decodePem(publicPem))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(decodePem(privatePem))));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid JWT RSA key configuration", e);
        }
    }

    @Bean
    JwtEncoder jwtEncoder(KeyPair jwtKeyPair) {
        return NimbusJwtEncoder.withKeyPair(
                        (RSAPublicKey) jwtKeyPair.getPublic(), (RSAPrivateKey) jwtKeyPair.getPrivate())
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(KeyPair jwtKeyPair, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) jwtKeyPair.getPublic())
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(timestamps);
        return decoder;
    }

    private static byte[] decodePem(String pem) {
        String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
