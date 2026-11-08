package br.dev.duofinance.support;

import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.Map;
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

    public UUID category(TestSession session, String name, CategoryKind kind) {
        var response = mvc.post()
                .uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\",\"kind\":\"%s\",\"color\":\"#112233\",\"icon\":\"tag\"}"
                        .formatted(name, kind))
                .exchange()
                .getResponse();
        if (response.getStatus() != 201) {
            throw new IllegalStateException("Category creation failed: " + response.getStatus());
        }
        try {
            return UUID.fromString(
                    json.readTree(response.getContentAsString()).path("id").asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    /** Creates a transaction through the API from a raw request body and returns its id. */
    public UUID transaction(TestSession session, Map<String, Object> body) {
        var response = mvc.post()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .exchange()
                .getResponse();
        if (response.getStatus() != 201) {
            throw new IllegalStateException("Transaction creation failed: " + response.getStatus());
        }
        try {
            return UUID.fromString(
                    json.readTree(response.getContentAsString()).path("id").asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
