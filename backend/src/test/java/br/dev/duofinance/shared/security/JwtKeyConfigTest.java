package br.dev.duofinance.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.shared.config.AppProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtKeyConfigTest {

    private final JwtKeyConfig config = new JwtKeyConfig();

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static AppProperties props(String privateKey, String publicKey) {
        return new AppProperties(null, null, new AppProperties.Auth(privateKey, publicKey, true), null);
    }

    @Test
    void configuredPemKeys_areLoaded() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair expected = generator.generateKeyPair();

        KeyPair loaded = config.jwtKeyPair(props(
                pem("PRIVATE KEY", expected.getPrivate().getEncoded()),
                pem("PUBLIC KEY", expected.getPublic().getEncoded())));

        assertThat(loaded.getPublic()).isEqualTo(expected.getPublic());
        assertThat(loaded.getPrivate()).isEqualTo(expected.getPrivate());
    }

    @Test
    void missingKeys_generateEphemeralPair() {
        assertThat(config.jwtKeyPair(props("", null)).getPublic().getAlgorithm())
                .isEqualTo("RSA");
        assertThat(config.jwtKeyPair(new AppProperties(null, null, null, null))).isNotNull();
    }

    @Test
    void onlyOneKey_failsFast() {
        assertThatThrownBy(() -> config.jwtKeyPair(props("x", ""))).isInstanceOf(IllegalStateException.class);
    }
}
