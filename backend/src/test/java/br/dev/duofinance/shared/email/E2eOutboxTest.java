package br.dev.duofinance.shared.email;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.TestcontainersConfiguration;
import br.dev.duofinance.support.DatabaseCleanupExtension;
import br.dev.duofinance.support.TestClockConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Runs with {@code test} + {@code e2e}. It deliberately omits {@code TestEmailConfiguration}: that one declares a
 * {@code @Primary RecordingEmailSender}, and a second {@code @Primary EmailSender} ({@link E2eOutbox}) would make
 * injection ambiguous. Here the outbox is the only sender, as it is when running {@code dev,e2e}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ActiveProfiles({"test", "e2e"})
@ExtendWith(DatabaseCleanupExtension.class)
class E2eOutboxTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    E2eOutbox outbox;

    @Test
    void outbox_recordsSentEmailsWithLinks() {
        String email = "outbox@example.com";
        assertThat(mvc.post()
                        .uri("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"s3cret-pass","displayName":"Ana","locale":"pt-BR",
                                 "acceptTerms":true,"termsVersion":"2026-11","accessCode":null}
                                """.formatted(email)))
                .hasStatus(201);

        Awaitility.await().until(() -> !outbox.sentTo(email).isEmpty());

        assertThat(mvc.get().uri("/api/v1/__e2e/outbox").param("to", email))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(1);
        assertThat(mvc.get().uri("/api/v1/__e2e/outbox").param("to", email))
                .bodyJson()
                .extractingPath("$[0].links[0]")
                .asString()
                .contains("/verify-email?token=");
    }
}
