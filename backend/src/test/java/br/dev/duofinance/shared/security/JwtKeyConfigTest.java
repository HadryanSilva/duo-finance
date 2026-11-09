package br.dev.duofinance.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.shared.config.AppProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class JwtKeyConfigTest {

    private final JwtKeyConfig config = new JwtKeyConfig();

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static AppProperties props(String privateKey, String publicKey) {
        return new AppProperties(null, null, new AppProperties.Auth(privateKey, publicKey, true), null);
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    private static KeyPair generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Test
    void configuredPemKeys_areLoaded() throws Exception {
        KeyPair expected = generate();

        KeyPair loaded = config.jwtKeyPair(
                props(
                        pem("PRIVATE KEY", expected.getPrivate().getEncoded()),
                        pem("PUBLIC KEY", expected.getPublic().getEncoded())),
                profiles());

        assertThat(loaded.getPublic()).isEqualTo(expected.getPublic());
        assertThat(loaded.getPrivate()).isEqualTo(expected.getPrivate());
    }

    @Test
    void missingKeys_inDevOrTest_generateEphemeralPair() {
        assertThat(config.jwtKeyPair(props("", null), profiles("dev"))
                        .getPublic()
                        .getAlgorithm())
                .isEqualTo("RSA");
        assertThat(config.jwtKeyPair(new AppProperties(null, null, null, null), profiles("test")))
                .isNotNull();
    }

    @Test
    void missingKeys_outsideDevAndTest_failFast() {
        assertThatThrownBy(() -> config.jwtKeyPair(props(null, null), profiles()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-private-key");
        assertThatThrownBy(() -> config.jwtKeyPair(new AppProperties(null, null, null, null), profiles("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyOneKey_failsFast() {
        assertThatThrownBy(() -> config.jwtKeyPair(props("x", ""), profiles("dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mismatchedKeys_failFast() throws Exception {
        KeyPair one = generate();
        KeyPair other = generate();

        assertThatThrownBy(() -> config.jwtKeyPair(
                        props(
                                pem("PRIVATE KEY", one.getPrivate().getEncoded()),
                                pem("PUBLIC KEY", other.getPublic().getEncoded())),
                        profiles("dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void pemFormats_singleLine_literalBackslashN_andRealNewlines_areAllAccepted() throws Exception {
        KeyPair expected = generate();
        String privateReal = pem("PRIVATE KEY", expected.getPrivate().getEncoded());
        String publicReal = pem("PUBLIC KEY", expected.getPublic().getEncoded());

        String[][] variants = {
            {privateReal.replace("\n", ""), publicReal.replace("\n", "")},
            {privateReal.replace("\n", "\n"), publicReal.replace("\n", "\n")},
            {privateReal, publicReal},
        };
        for (String[] variant : variants) {
            KeyPair loaded = config.jwtKeyPair(props(variant[0], variant[1]), profiles());
            assertThat(loaded.getPublic()).isEqualTo(expected.getPublic());
            assertThat(loaded.getPrivate()).isEqualTo(expected.getPrivate());
        }
    }
}
