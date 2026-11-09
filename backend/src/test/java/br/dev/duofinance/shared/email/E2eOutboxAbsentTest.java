package br.dev.duofinance.shared.email;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class E2eOutboxAbsentTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void outbox_isAbsentWithoutE2eProfile() {
        assertThat(context.getBeanNamesForType(E2eOutbox.class)).isEmpty();
        assertThat(context.getBeanNamesForType(E2eOutboxController.class)).isEmpty();
        int status = mvc.get()
                .uri("/api/v1/__e2e/outbox")
                .param("to", "a@example.com")
                .exchange()
                .getResponse()
                .getStatus();
        assertThat(status).isIn(401, 404);
    }
}
