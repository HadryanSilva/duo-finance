package br.dev.duofinance.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Boots the real {@code prod} profile with the minimal environment variables the deploy provides. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
class ProdProfileTest {

    @DynamicPropertySource
    static void prodEnvironment(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        // The Testcontainers @ServiceConnection supplies the real connection; these only satisfy the placeholders.
        registry.add("DB_URL", () -> "jdbc:postgresql://unused:5432/unused");
        registry.add("DB_USER", () -> "unused");
        registry.add("DB_PASSWORD", () -> "unused");
        registry.add("PUBLIC_BASE_URL", () -> "https://duofinance.dev.br");
        registry.add("JWT_PRIVATE_KEY", () -> pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        registry.add("JWT_PUBLIC_KEY", () -> pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        registry.add("RESEND_API_KEY", () -> "re_test");
        registry.add("EMAIL_FROM", () -> "Duo Finance <no-reply@duofinance.dev.br>");
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Autowired
    Environment env;

    @Autowired
    DataSource dataSource;

    @Autowired
    AppProperties properties;

    @Test
    void prodProfile_logsJsonAndTrustsForwardedHeaders() {
        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }

    @Test
    void prodProfile_wiresAppSettingsFromEnvironment() {
        assertThat(properties.publicBaseUrl()).hasToString("https://duofinance.dev.br");
        assertThat(properties.auth().cookieSecure()).isTrue();
        assertThat(properties.email().provider()).isEqualTo(AppProperties.EmailProvider.RESEND);
        assertThat(properties.email().from()).isEqualTo("Duo Finance <no-reply@duofinance.dev.br>");
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(10);
    }
}
