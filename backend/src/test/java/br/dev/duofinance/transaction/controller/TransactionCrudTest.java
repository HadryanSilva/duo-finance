package br.dev.duofinance.transaction.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class TransactionCrudTest {

    private static final String BASE = "/api/v1/transactions";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private MvcTestResult send(HttpMethod method, TestSession s, String uri, Map<String, Object> body) {
        var builder = mvc.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s));
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return builder.exchange();
    }

    private static Map<String, Object> expense(UUID category, UUID bank) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "EXPENSE");
        body.put("amountCents", 1250);
        body.put("date", "2026-11-05");
        body.put("description", "  Lunch  ");
        body.put("categoryId", category);
        if (bank != null) {
            body.put("bankAccountId", bank);
        }
        return body;
    }

    private static Map<String, Object> transfer(UUID from, UUID to, UUID category) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "TRANSFER");
        body.put("amountCents", 500);
        body.put("date", "2026-11-05");
        body.put("bankAccountId", from);
        body.put("destinationBankAccountId", to);
        body.put("categoryId", category);
        return body;
    }

    private UUID seeded(TestSession s, String kind) {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = ? LIMIT 1", UUID.class, s.accountId(), kind);
    }

    private void archive(String table, UUID id) {
        jdbc.update("UPDATE " + table + " SET archived_at = now() WHERE id = ?", id);
    }

    @Test
    void createExpense_returns201WithAuthorAndRefs() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID bank = fixtures.bankAccount(s, "Main", 0);

        var result = send(HttpMethod.POST, s, BASE, expense(category, bank));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.kind").isEqualTo("EXPENSE");
        assertThat(result).bodyJson().extractingPath("$.amountCents").isEqualTo(1250);
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Lunch");
        assertThat(result).bodyJson().extractingPath("$.category.id").isEqualTo(category.toString());
        assertThat(result).bodyJson().extractingPath("$.category.archived").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.bankAccount.name").isEqualTo("Main");
        assertThat(result).bodyJson().extractingPath("$.destinationBankAccount").isNull();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.createdBy.userId")
                .isEqualTo(s.userId().toString());
        assertThat(result).bodyJson().extractingPath("$.createdBy.displayName").isEqualTo("Test User");
        assertThat(result).bodyJson().extractingPath("$.recurringRuleId").isNull();

        String id = json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asString();
        assertThat(send(HttpMethod.GET, s, BASE + "/" + id, null)).hasStatus(HttpStatus.OK);
    }

    @Test
    void createTransfer_returnsBothBankRefs() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        Map<String, Object> body = transfer(a, b, null);
        body.remove("categoryId");

        var result = send(HttpMethod.POST, s, BASE, body);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.bankAccount.name").isEqualTo("A");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.destinationBankAccount.name")
                .isEqualTo("B");
        assertThat(result).bodyJson().extractingPath("$.category").isNull();
    }

    @Test
    void createIncome_withExpenseCategory_returns400KindMismatch() {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> body = expense(seeded(s, "EXPENSE"), null);
        body.put("kind", "INCOME");

        var result = send(HttpMethod.POST, s, BASE, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_KIND_MISMATCH");
    }

    @Test
    void createIncomeWithoutCategory_returns400FieldCategoryId() {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> body = expense(null, null);
        body.put("kind", "INCOME");

        var result = send(HttpMethod.POST, s, BASE, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("categoryId");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isNotNull();
    }

    @Test
    void expenseWithDestination_returns400FieldDestinationBankAccountId() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        Map<String, Object> body = expense(seeded(s, "EXPENSE"), bank);
        body.put("destinationBankAccountId", bank);

        var result = send(HttpMethod.POST, s, BASE, body);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("destinationBankAccountId");
    }

    @Test
    void transfer_requiresDistinctOriginAndDestination() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);

        Map<String, Object> noDestination = transfer(a, b, null);
        noDestination.remove("destinationBankAccountId");
        Map<String, Object> noOrigin = transfer(a, b, null);
        noOrigin.remove("bankAccountId");

        for (Map<String, Object> body : java.util.List.of(
                noDestination, noOrigin, transfer(a, a, null), transfer(a, b, seeded(s, "EXPENSE")))) {
            var result = send(HttpMethod.POST, s, BASE, body);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_INVALID");
        }
    }

    @Test
    void create_withArchivedCategory_returns409() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(s, "Old", CategoryKind.EXPENSE);
        archive("categories", category);

        var result = send(HttpMethod.POST, s, BASE, expense(category, null));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_ARCHIVED");
    }

    @Test
    void create_withArchivedBankAccount_returns409() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Old", 0);
        archive("bank_accounts", bank);

        var result = send(HttpMethod.POST, s, BASE, expense(seeded(s, "EXPENSE"), bank));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_ARCHIVED");
    }

    @Test
    void update_keepingArchivedCategory_isAllowed_butSwitchingToArchived_isNot() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID kept = fixtures.category(s, "Kept", CategoryKind.EXPENSE);
        UUID other = fixtures.category(s, "Other", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        UUID id = fixtures.transaction(s, expense(kept, bank));
        archive("categories", kept);
        archive("categories", other);
        archive("bank_accounts", bank);

        Map<String, Object> keeping = expense(kept, bank);
        keeping.put("amountCents", 9999);
        var ok = send(HttpMethod.PUT, s, BASE + "/" + id, keeping);
        assertThat(ok).hasStatus(HttpStatus.OK);
        assertThat(ok).bodyJson().extractingPath("$.amountCents").isEqualTo(9999);
        assertThat(ok).bodyJson().extractingPath("$.category.archived").isEqualTo(true);

        var switching = send(HttpMethod.PUT, s, BASE + "/" + id, expense(other, bank));
        assertThat(switching).hasStatus(HttpStatus.CONFLICT);
        assertThat(switching).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_ARCHIVED");

        UUID otherBank = fixtures.bankAccount(s, "Other bank", 0);
        archive("bank_accounts", otherBank);
        var switchingBank = send(HttpMethod.PUT, s, BASE + "/" + id, expense(kept, otherBank));
        assertThat(switchingBank).hasStatus(HttpStatus.CONFLICT);
        assertThat(switchingBank).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_ARCHIVED");
    }

    @Test
    void create_withOtherAccountsCategory_returns404() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bia = testUsers.verified("bia@mail.com");

        var result = send(HttpMethod.POST, ana, BASE, expense(seeded(bia, "EXPENSE"), null));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void create_withOtherAccountsBankAccount_returns404() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bia = testUsers.verified("bia@mail.com");
        UUID foreign = fixtures.bankAccount(bia, "Hers", 0);

        assertThat(send(HttpMethod.POST, ana, BASE, expense(seeded(ana, "EXPENSE"), foreign)))
                .hasStatus(HttpStatus.NOT_FOUND);

        UUID mine = fixtures.bankAccount(ana, "Mine", 0);
        assertThat(send(HttpMethod.POST, ana, BASE, transfer(mine, foreign, null)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void amountZeroOrNegative_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");
        for (long amount : new long[] {0, -1, 100_000_000_001L}) {
            Map<String, Object> body = expense(seeded(s, "EXPENSE"), null);
            body.put("amountCents", amount);
            var result = send(HttpMethod.POST, s, BASE, body);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amountCents");
        }
    }

    @Test
    void dateOutOfRange_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");
        for (String date : new String[] {"1969-12-31", "2101-01-01"}) {
            Map<String, Object> body = expense(seeded(s, "EXPENSE"), null);
            body.put("date", date);
            var result = send(HttpMethod.POST, s, BASE, body);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
            assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("date");
        }
        Map<String, Object> edge = expense(seeded(s, "EXPENSE"), null);
        edge.put("date", "1970-01-01");
        assertThat(send(HttpMethod.POST, s, BASE, edge)).hasStatus(HttpStatus.CREATED);
        edge.put("date", "2100-12-31");
        assertThat(send(HttpMethod.POST, s, BASE, edge)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void blankDescription_isStoredAsNull_andTooLongIsRejected() {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> body = expense(seeded(s, "EXPENSE"), null);
        body.put("description", "   ");
        assertThat(send(HttpMethod.POST, s, BASE, body))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.description")
                .isNull();

        body.put("description", "x".repeat(141));
        assertThat(send(HttpMethod.POST, s, BASE, body)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void partnerSeesAndEditsTransaction_createdByStaysOriginalAuthor() {
        TestSession[] couple = testUsers.couple("ana@mail.com", "bia@mail.com");
        TestSession ana = couple[0];
        TestSession bia = couple[1];
        UUID id = fixtures.transaction(ana, expense(seeded(ana, "EXPENSE"), null));

        assertThat(send(HttpMethod.GET, bia, BASE + "/" + id, null)).hasStatus(HttpStatus.OK);
        Map<String, Object> edit = expense(seeded(ana, "EXPENSE"), null);
        edit.put("amountCents", 777);
        var result = send(HttpMethod.PUT, bia, BASE + "/" + id, edit);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.amountCents").isEqualTo(777);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.createdBy.userId")
                .isEqualTo(ana.userId().toString());
    }

    @Test
    void deletedAuthor_showsNullCreatedBy() {
        TestSession[] couple = testUsers.couple("ana@mail.com", "bia@mail.com");
        TestSession ana = couple[0];
        TestSession bia = couple[1];
        UUID id = fixtures.transaction(bia, expense(seeded(bia, "EXPENSE"), null));

        jdbc.update("DELETE FROM users WHERE id = ?", bia.userId());

        var result = send(HttpMethod.GET, ana, BASE + "/" + id, null);
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.createdBy").isNull();
    }

    @Test
    void otherAccountsTransaction_returns404() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bia = testUsers.verified("bia@mail.com");
        UUID id = fixtures.transaction(ana, expense(seeded(ana, "EXPENSE"), null));

        assertThat(send(HttpMethod.GET, bia, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PUT, bia, BASE + "/" + id, expense(seeded(bia, "EXPENSE"), null)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, bia, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void delete_returns204_thenGetReturns404() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.transaction(s, expense(seeded(s, "EXPENSE"), null));

        assertThat(send(HttpMethod.DELETE, s, BASE + "/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.GET, s, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_replacesKindAndReferences() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        UUID id = fixtures.transaction(s, expense(seeded(s, "EXPENSE"), a));
        Map<String, Object> body = transfer(a, b, null);
        body.remove("categoryId");

        var result = send(HttpMethod.PUT, s, BASE + "/" + id, body);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.kind").isEqualTo("TRANSFER");
        assertThat(result).bodyJson().extractingPath("$.category").isNull();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.destinationBankAccount.name")
                .isEqualTo("B");
    }
}
