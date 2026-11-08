package br.dev.duofinance.support;

import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** Creates finance data through the real API. */
@TestComponent
public class FinanceFixtures {

    private final MockMvcTester mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    public FinanceFixtures(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public UUID bankAccount(TestSession session, String name, long initialCents) {
        var response = mvc.post()
                .uri("/api/v1/bank-accounts")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"type\":\"CHECKING\",\"initialBalanceCents\":%d}"
                        .formatted(name, initialCents))
                .exchange()
                .getResponse();
        if (response.getStatus() != 201) {
            throw new IllegalStateException("Bank account creation failed: " + response.getStatus());
        }
        try {
            return UUID.fromString(
                    json.readTree(response.getContentAsString()).path("id").asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
