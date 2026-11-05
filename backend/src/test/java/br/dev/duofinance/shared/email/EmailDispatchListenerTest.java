package br.dev.duofinance.shared.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.RecordingEmailSender;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class EmailDispatchListenerTest {

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    RecordingEmailSender sender;

    private final EmailRequestedEvent event = new EmailRequestedEvent(
            "ana@mail.com", EmailTemplate.VERIFY_EMAIL, "en", Map.of("name", "Ana", "link", "https://x/v?token=t"));

    @BeforeEach
    void clear() {
        sender.clear();
    }

    @Test
    void event_isSentOnlyAfterCommit() throws Exception {
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(event);
            s.setRollbackOnly();
        });
        Thread.sleep(300);
        assertThat(sender.sent()).isEmpty();

        tx.executeWithoutResult(s -> publisher.publishEvent(event));
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(sender.sent()).singleElement().satisfies(m -> {
                    assertThat(m.to()).isEqualTo("ana@mail.com");
                    assertThat(m.subject()).isEqualTo("Confirm your email on Duo Finance");
                }));
    }

    @Test
    void senderFailure_doesNotPropagate() {
        EmailSender failing = m -> {
            throw new IllegalStateException("boom");
        };
        var listener = new EmailDispatchListener(new EmailTemplateRenderer(), failing);

        listener.on(event); // must not throw
    }
}
