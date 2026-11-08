package br.dev.duofinance.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** Every route that takes an id must treat a resource of another Account as nonexistent. */
@IntegrationTest
class AccountIsolationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    private final JsonMapper json = JsonMapper.builder().build();

    static Stream<Arguments> idRoutes() {
        return Stream.of(
                Arguments.of("bank-accounts", "GET", ""),
                Arguments.of("bank-accounts", "PUT", ""),
                Arguments.of("bank-accounts", "DELETE", ""),
                Arguments.of("bank-accounts", "POST", "/archive"),
                Arguments.of("bank-accounts", "POST", "/unarchive"),
                Arguments.of("categories", "GET", ""),
                Arguments.of("categories", "PUT", ""),
                Arguments.of("categories", "DELETE", ""),
                Arguments.of("categories", "POST", "/archive"),
                Arguments.of("categories", "POST", "/unarchive"),
                Arguments.of("transactions", "GET", ""),
                Arguments.of("transactions", "PUT", ""),
                Arguments.of("transactions", "DELETE", ""),
                Arguments.of("recurring-rules", "GET", ""),
                Arguments.of("recurring-rules", "PUT", ""),
                Arguments.of("recurring-rules", "DELETE", ""),
                Arguments.of("recurring-rules", "POST", "/activate"),
                Arguments.of("recurring-rules", "POST", "/deactivate"),
                Arguments.of("budgets", "GET", ""),
                Arguments.of("budgets", "PUT", ""),
                Arguments.of("budgets", "DELETE", ""));
    }

    @ParameterizedTest(name = "{1} /{0}/id{2} of another account returns 404")
    @MethodSource("idRoutes")
    void foreignId_returns404(String resource, String method, String suffix) {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);

        var request = mvc.method(HttpMethod.valueOf(method))
                .uri("/api/v1/" + resource + "/" + ids.get(resource) + suffix)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder));
        if (method.equals("PUT")) {
            request.contentType(MediaType.APPLICATION_JSON).content(updateBody(resource, ids));
        }
        var result = request.exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void foreignDelete_leavesResourceUntouched() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);

        mvc.delete()
                .uri("/api/v1/transactions/" + ids.get("transactions"))
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();

        assertThat(mvc.get()
                        .uri("/api/v1/transactions/" + ids.get("transactions"))
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(owner))
                        .exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void referencingForeignCategoryOrBankAccount_returns404() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);

        var foreignCategory = mvc.post()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        ids.get("categories"))))
                .exchange();
        assertThat(foreignCategory).hasStatus(HttpStatus.NOT_FOUND);

        UUID ownCategory = fixtures.category(intruder, "Cat Teste A", CategoryKind.EXPENSE);
        var foreignBank = mvc.post()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        ownCategory,
                        "bankAccountId",
                        ids.get("bank-accounts"))))
                .exchange();
        assertThat(foreignBank).hasStatus(HttpStatus.NOT_FOUND);

        var foreignFilter = mvc.get()
                .uri("/api/v1/transactions?categoryId=" + ids.get("categories"))
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();
        assertThat(foreignFilter).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void lists_neverShowOtherAccountsData() throws Exception {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        createAll(owner);

        for (String path : new String[] {"bank-accounts", "transactions", "recurring-rules", "budgets"}) {
            var result = mvc.get()
                    .uri("/api/v1/" + path)
                    .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                    .exchange();
            assertThat(result).hasStatus(HttpStatus.OK);
            var root = json.readTree(result.getResponse().getContentAsString());
            var items = root.has("content") ? root.path("content") : root;
            assertThat(items.size()).as(path).isZero();
        }
    }

    private Map<String, UUID> createAll(TestSession owner) {
        Map<String, UUID> ids = new HashMap<>();
        UUID expense = fixtures.category(owner, "Cat Teste A", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(owner, "Conta", 1000);
        ids.put("categories", expense);
        ids.put("bank-accounts", bank);
        ids.put(
                "transactions",
                fixtures.transaction(
                        owner,
                        Map.of(
                                "kind",
                                "EXPENSE",
                                "amountCents",
                                500,
                                "date",
                                "2026-11-01",
                                "categoryId",
                                expense,
                                "bankAccountId",
                                bank)));
        ids.put(
                "recurring-rules",
                create(
                        owner,
                        "recurring-rules",
                        Map.of(
                                "kind",
                                "EXPENSE",
                                "amountCents",
                                700,
                                "categoryId",
                                expense,
                                "bankAccountId",
                                bank,
                                "frequency",
                                "MONTHLY",
                                "startDate",
                                "2099-01-01")));
        UUID budgetCategory = fixtures.category(owner, "Cat Teste B", CategoryKind.EXPENSE);
        ids.put("budgets", create(owner, "budgets", Map.of("categoryId", budgetCategory, "monthlyLimitCents", 9000)));
        return ids;
    }

    private UUID create(TestSession session, String resource, Map<String, Object> body) {
        var response = mvc.post()
                .uri("/api/v1/" + resource)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .exchange()
                .getResponse();
        if (response.getStatus() != 201) {
            throw new IllegalStateException(resource + " creation failed: " + response.getStatus());
        }
        try {
            return UUID.fromString(
                    json.readTree(response.getContentAsString()).path("id").asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal valid body per resource, so a PUT reaches the lookup instead of failing validation. */
    private String updateBody(String resource, Map<String, UUID> ids) {
        Map<String, Object> body = switch (resource) {
            case "bank-accounts" -> Map.of("name", "X", "type", "CHECKING", "initialBalanceCents", 0);
            case "categories" -> Map.of("name", "X", "color", "#112233", "icon", "tag");
            case "transactions" ->
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        ids.get("categories"));
            case "recurring-rules" -> Map.of("amountCents", 100, "categoryId", ids.get("categories"));
            case "budgets" -> Map.of("monthlyLimitCents", 100);
            default -> throw new IllegalArgumentException(resource);
        };
        return json.writeValueAsString(body);
    }
}
