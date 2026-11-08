package br.dev.duofinance.account.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class FinanceLifecycleTest {

    private static final String[] FINANCE_TABLES = {"transactions", "recurring_rules", "budgets", "bank_accounts"};

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private UUID seedTransaction(TestSession s) {
        UUID category = fixtures.category(s, "Cat Teste A", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(s, "Conta", 1000);
        return fixtures.transaction(
                s,
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        500,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        category,
                        "bankAccountId",
                        bank));
    }

    private void seedEverything(TestSession s) {
        UUID category = fixtures.category(s, "Cat Teste B", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(s, "Conta", 1000);
        fixtures.transaction(
                s,
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        500,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        category,
                        "bankAccountId",
                        bank));
        post(
                s,
                "/api/v1/recurring-rules",
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        700,
                        "categoryId",
                        category,
                        "bankAccountId",
                        bank,
                        "frequency",
                        "MONTHLY",
                        "startDate",
                        "2099-01-01"));
        post(s, "/api/v1/budgets", Map.of("categoryId", category, "monthlyLimitCents", 9000));
    }

    private void post(TestSession s, String uri, Map<String, Object> body) {
        var result = mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private long count(String table, UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, accountId);
    }

    private MvcTestResult accept(TestSession s, String token, boolean confirmDiscard) {
        return mvc.post()
                .uri("/api/v1/account/invitations/" + token + "/accept")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmDiscard\":" + confirmDiscard + "}")
                .exchange();
    }

    private MvcTestResult deleteMe(TestSession s) {
        return mvc.delete()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + TestUsers.PASSWORD + "\"}")
                .exchange();
    }

    @Test
    void accept_withFinanceDataInSoloAccount_requiresConfirmDiscard() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        seedTransaction(rui);
        assertThat(mvc.post()
                        .uri("/api/v1/account/invitations")
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rui@mail.com\"}")
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
        String token = testUsers.invitationToken("rui@mail.com");

        var refused = accept(rui, token, false);
        assertThat(refused).hasStatus(HttpStatus.CONFLICT);
        assertThat(refused).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_HAS_DATA");
        assertThat(count("transactions", rui.accountId())).isEqualTo(1);

        assertThat(accept(rui, token, true)).hasStatus(HttpStatus.OK);
        assertThat(count("transactions", rui.accountId())).isZero();
        assertThat(count("bank_accounts", rui.accountId())).isZero();
    }

    @Test
    void leave_leaverLosesData_partnerKeepsIt() throws Exception {
        TestSession[] pair = testUsers.couple("ana@mail.com", "rui@mail.com");
        UUID tx = seedTransaction(pair[0]);

        var left = mvc.post()
                .uri("/api/v1/account/leave")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(pair[1]))
                .exchange();
        assertThat(left).hasStatus(HttpStatus.OK);
        String newToken = json.readTree(left.getResponse().getContentAsString())
                .path("accessToken")
                .asString();

        assertThat(mvc.get()
                        .uri("/api/v1/transactions/" + tx)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken)
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        var leaverList = mvc.get()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + newToken)
                .exchange();
        assertThat(leaverList).bodyJson().extractingPath("$.totalElements").isEqualTo(0);
        assertThat(mvc.get()
                        .uri("/api/v1/transactions/" + tx)
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(pair[0]))
                        .exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void deleteMe_soleMemberWithFinanceData_returns204() {
        TestSession ana = testUsers.verified("ana@mail.com");
        seedEverything(ana);
        for (String table : FINANCE_TABLES) {
            assertThat(count(table, ana.accountId())).as(table).isPositive();
        }

        assertThat(deleteMe(ana)).hasStatus(HttpStatus.NO_CONTENT);

        for (String table : FINANCE_TABLES) {
            assertThat(count(table, ana.accountId())).as(table).isZero();
        }
        assertThat(count("categories", ana.accountId())).isZero();
    }

    @Test
    void deletePartner_keepsTransactionsWithNullAuthor() {
        TestSession[] pair = testUsers.couple("ana@mail.com", "rui@mail.com");
        UUID tx = seedTransaction(pair[1]);

        assertThat(deleteMe(pair[1])).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.get()
                        .uri("/api/v1/transactions/" + tx)
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(pair[0]))
                        .exchange())
                .hasStatus(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT created_by FROM transactions WHERE id = ?", UUID.class, tx))
                .isNull();
        assertThat(count("transactions", pair[0].accountId())).isEqualTo(1);
    }
}
