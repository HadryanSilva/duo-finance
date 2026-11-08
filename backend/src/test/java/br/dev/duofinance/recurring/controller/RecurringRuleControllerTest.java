package br.dev.duofinance.recurring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.recurring.service.RecurringGenerator;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class RecurringRuleControllerTest {

    private static final String BASE = "/api/v1/recurring-rules";
    private static final Instant TODAY = Instant.parse("2027-03-15T10:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RecurringGenerator generator;

    @Autowired
    TransactionTemplate tx;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setClock() {
        clock.setInstant(TODAY);
    }

    private MvcTestResult send(HttpMethod method, TestSession s, String uri, Map<String, Object> body) {
        var builder = mvc.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s));
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return builder.exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A fresh access token after the clock moved past the 15-minute lifetime of the previous one. */
    private TestSession relogin(TestSession s, String email) throws Exception {
        var result = mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, TestUsers.PASSWORD))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return new TestSession(
                s.userId(), s.accountId(), body(result).path("accessToken").asString(), s.refreshCookie());
    }

    private UUID seeded(TestSession s, String kind) {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = ? ORDER BY name LIMIT 1",
                UUID.class,
                s.accountId(),
                kind);
    }

    private static Map<String, Object> monthlyExpense(UUID category, String startDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "EXPENSE");
        body.put("amountCents", 1000);
        body.put("description", "  Rent  ");
        body.put("categoryId", category);
        body.put("frequency", "MONTHLY");
        body.put("startDate", startDate);
        return body;
    }

    private static Map<String, Object> updateOf(Map<String, Object> create) {
        Map<String, Object> body = new HashMap<>(create);
        body.remove("kind");
        body.remove("frequency");
        body.remove("startDate");
        return body;
    }

    private UUID create(TestSession s, Map<String, Object> body) throws Exception {
        var result = send(HttpMethod.POST, s, BASE, body);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(body(result).path("id").asString());
    }

    private List<LocalDate> generatedDates(UUID ruleId) {
        return jdbc.queryForList(
                "SELECT date FROM transactions WHERE recurring_rule_id = ? ORDER BY date", LocalDate.class, ruleId);
    }

    private int transactionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);
    }

    @Test
    void create_withPastStart_generatesAllDueOccurrencesImmediately() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        Map<String, Object> request = monthlyExpense(category, "2027-01-31");
        request.put("bankAccountId", bank);

        var result = send(HttpMethod.POST, s, BASE, request);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.kind").isEqualTo("EXPENSE");
        assertThat(result).bodyJson().extractingPath("$.amountCents").isEqualTo(1000);
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("Rent");
        assertThat(result).bodyJson().extractingPath("$.category.id").isEqualTo(category.toString());
        assertThat(result).bodyJson().extractingPath("$.bankAccount.name").isEqualTo("Main");
        assertThat(result).bodyJson().extractingPath("$.destinationBankAccount").isNull();
        assertThat(result).bodyJson().extractingPath("$.frequency").isEqualTo("MONTHLY");
        assertThat(result).bodyJson().extractingPath("$.startDate").isEqualTo("2027-01-31");
        assertThat(result).bodyJson().extractingPath("$.endDate").isNull();
        assertThat(result).bodyJson().extractingPath("$.nextRunDate").isEqualTo("2027-03-31");
        assertThat(result).bodyJson().extractingPath("$.active").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.finished").isEqualTo(false);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.createdBy.userId")
                .isEqualTo(s.userId().toString());

        UUID ruleId = UUID.fromString(body(result).path("id").asString());
        assertThat(generatedDates(ruleId))
                .containsExactly(LocalDate.parse("2027-01-31"), LocalDate.parse("2027-02-28"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT kind, amount_cents, description, category_id, bank_account_id, destination_bank_account_id,"
                        + " created_by, account_id FROM transactions WHERE recurring_rule_id = ? AND date = ?",
                ruleId,
                LocalDate.parse("2027-02-28"));
        assertThat(row.get("kind")).isEqualTo("EXPENSE");
        assertThat(row.get("amount_cents")).isEqualTo(1000L);
        assertThat(row.get("description")).isEqualTo("Rent");
        assertThat(row.get("category_id")).isEqualTo(category);
        assertThat(row.get("bank_account_id")).isEqualTo(bank);
        assertThat(row.get("destination_bank_account_id")).isNull();
        assertThat(row.get("created_by")).isEqualTo(s.userId());
        assertThat(row.get("account_id")).isEqualTo(s.accountId());

        // The generated transactions are ordinary transactions linked to the rule.
        var listed = send(HttpMethod.GET, s, "/api/v1/transactions", null);
        assertThat(listed).bodyJson().extractingPath("$.totalElements").isEqualTo(2);
        assertThat(listed)
                .bodyJson()
                .extractingPath("$.content[0].recurringRuleId")
                .isEqualTo(ruleId.toString());
        assertThat(send(HttpMethod.GET, s, BASE + "/" + ruleId, null))
                .bodyJson()
                .extractingPath("$.nextRunDate")
                .isEqualTo("2027-03-31");
    }

    @Test
    void create_occurrenceOnToday_isGenerated() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID ruleId = create(s, monthlyExpense(seeded(s, "EXPENSE"), "2027-03-15"));

        assertThat(generatedDates(ruleId)).containsExactly(LocalDate.parse("2027-03-15"));
        assertThat(send(HttpMethod.GET, s, BASE + "/" + ruleId, null))
                .bodyJson()
                .extractingPath("$.nextRunDate")
                .isEqualTo("2027-04-15");
    }

    @Test
    void create_withFutureStart_generatesNothing() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = send(HttpMethod.POST, s, BASE, monthlyExpense(seeded(s, "EXPENSE"), "2027-04-01"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.nextRunDate").isEqualTo("2027-04-01");
        assertThat(result).bodyJson().extractingPath("$.finished").isEqualTo(false);
        assertThat(transactionCount()).isZero();
    }

    @Test
    void create_withEndDateInPast_generatesUpToEndDateAndIsFinished() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> request = monthlyExpense(seeded(s, "EXPENSE"), "2027-01-31");
        request.put("endDate", "2027-03-30");

        var result = send(HttpMethod.POST, s, BASE, request);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.endDate").isEqualTo("2027-03-30");
        assertThat(result).bodyJson().extractingPath("$.nextRunDate").isEqualTo("2027-03-31");
        assertThat(result).bodyJson().extractingPath("$.finished").isEqualTo(true);
        UUID ruleId = UUID.fromString(body(result).path("id").asString());
        assertThat(generatedDates(ruleId))
                .containsExactly(LocalDate.parse("2027-01-31"), LocalDate.parse("2027-02-28"));
    }

    @Test
    void update_affectsOnlyFutureOccurrences() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        Map<String, Object> request = monthlyExpense(category, "2027-01-31");
        UUID ruleId = create(s, request);

        Map<String, Object> update = updateOf(request);
        update.put("amountCents", 2000);
        update.put("description", "New rent");
        var result = send(HttpMethod.PUT, s, BASE + "/" + ruleId, update);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.amountCents").isEqualTo(2000);
        assertThat(result).bodyJson().extractingPath("$.description").isEqualTo("New rent");
        assertThat(result).bodyJson().extractingPath("$.startDate").isEqualTo("2027-01-31");
        assertThat(result).bodyJson().extractingPath("$.nextRunDate").isEqualTo("2027-03-31");
        assertThat(jdbc.queryForList(
                        "SELECT amount_cents FROM transactions WHERE recurring_rule_id = ? ORDER BY date",
                        Long.class,
                        ruleId))
                .containsExactly(1000L, 1000L);

        clock.setInstant(Instant.parse("2027-04-01T00:15:00Z"));
        Integer inserted = tx.execute(status -> generator.generateDue(ruleId, LocalDate.parse("2027-04-01")));

        assertThat(inserted).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT amount_cents FROM transactions WHERE recurring_rule_id = ? ORDER BY date",
                        Long.class,
                        ruleId))
                .containsExactly(1000L, 1000L, 2000L);
        assertThat(jdbc.queryForObject(
                        "SELECT description FROM transactions WHERE recurring_rule_id = ? AND date = ?",
                        String.class,
                        ruleId,
                        LocalDate.parse("2027-03-31")))
                .isEqualTo("New rent");
    }

    @Test
    void generateDue_twiceOnSameDay_insertsNothingNew() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID ruleId = create(s, monthlyExpense(seeded(s, "EXPENSE"), "2027-01-31"));
        jdbc.update("UPDATE recurring_rules SET next_run_date = ? WHERE id = ?", LocalDate.parse("2027-01-31"), ruleId);

        Integer inserted = tx.execute(status -> generator.generateDue(ruleId, LocalDate.parse("2027-03-15")));

        assertThat(inserted).isZero();
        assertThat(generatedDates(ruleId)).hasSize(2);
    }

    @Test
    void deactivateThenActivate_skipsMissedOccurrences() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID ruleId = create(s, monthlyExpense(seeded(s, "EXPENSE"), "2027-01-15"));
        assertThat(generatedDates(ruleId)).hasSize(3);

        var deactivated = send(HttpMethod.POST, s, BASE + "/" + ruleId + "/deactivate", null);
        assertThat(deactivated).hasStatus(HttpStatus.OK);
        assertThat(deactivated).bodyJson().extractingPath("$.active").isEqualTo(false);

        // Inactive rules generate nothing.
        Integer insertedWhileInactive =
                tx.execute(status -> generator.generateDue(ruleId, LocalDate.parse("2027-04-15")));
        assertThat(insertedWhileInactive).isZero();

        clock.setInstant(Instant.parse("2027-05-15T09:00:00Z"));
        TestSession fresh = relogin(s, "ana@mail.com");
        var activated = send(HttpMethod.POST, fresh, BASE + "/" + ruleId + "/activate", null);

        assertThat(activated).hasStatus(HttpStatus.OK);
        assertThat(activated).bodyJson().extractingPath("$.active").isEqualTo(true);
        assertThat(activated).bodyJson().extractingPath("$.nextRunDate").isEqualTo("2027-06-15");
        assertThat(generatedDates(ruleId))
                .containsExactly(
                        LocalDate.parse("2027-01-15"),
                        LocalDate.parse("2027-02-15"),
                        LocalDate.parse("2027-03-15"),
                        LocalDate.parse("2027-05-15"));
    }

    @Test
    void delete_keepsGeneratedTransactionsDetached() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID ruleId = create(s, monthlyExpense(seeded(s, "EXPENSE"), "2027-01-31"));

        assertThat(send(HttpMethod.DELETE, s, BASE + "/" + ruleId, null)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(send(HttpMethod.GET, s, BASE + "/" + ruleId, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_rules", Integer.class))
                .isZero();
        assertThat(transactionCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transactions WHERE recurring_rule_id IS NULL", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void endDateBeforeStart_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> request = monthlyExpense(seeded(s, "EXPENSE"), "2027-01-31");
        request.put("endDate", "2027-01-30");

        var result = send(HttpMethod.POST, s, BASE, request);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("endDate");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("OutOfRange");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_rules", Integer.class))
                .isZero();

        // endDate equal to startDate is a single occurrence.
        request.put("endDate", "2027-01-31");
        UUID ruleId = create(s, request);
        assertThat(generatedDates(ruleId)).containsExactly(LocalDate.parse("2027-01-31"));

        // The same rule applies on update, against the rule's fixed startDate.
        Map<String, Object> update = updateOf(request);
        update.put("endDate", "2027-01-01");
        var updated = send(HttpMethod.PUT, s, BASE + "/" + ruleId, update);
        assertThat(updated).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(updated).bodyJson().extractingPath("$.errors[0].field").isEqualTo("endDate");
        assertThat(updated).bodyJson().extractingPath("$.errors[0].code").isEqualTo("OutOfRange");
    }

    @Test
    void startDateOutOfRange_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> request = monthlyExpense(seeded(s, "EXPENSE"), "1969-12-31");

        var result = send(HttpMethod.POST, s, BASE, request);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("startDate");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("OutOfRange");

        request.put("startDate", "2101-01-01");
        assertThat(send(HttpMethod.POST, s, BASE, request))
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("startDate");
    }

    @Test
    void missingRequiredFields_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> request = monthlyExpense(seeded(s, "EXPENSE"), "2027-01-31");
        request.remove("frequency");

        var result = send(HttpMethod.POST, s, BASE, request);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("frequency");

        request.put("frequency", "MONTHLY");
        request.put("amountCents", 0);
        assertThat(send(HttpMethod.POST, s, BASE, request))
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("amountCents");
    }

    @Test
    void archiveCategoryUsedByActiveRule_returns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID ruleId = create(s, monthlyExpense(category, "2027-04-01"));

        var result = send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IN_USE_BY_ACTIVE_RULE");
        assertThat(jdbc.queryForObject("SELECT archived_at FROM categories WHERE id = ?", Instant.class, category))
                .isNull();

        // An inactive rule no longer blocks archiving.
        send(HttpMethod.POST, s, BASE + "/" + ruleId + "/deactivate", null);
        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void archiveBankAccountUsedByActiveRule_returns409_inAnySlot() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID origin = fixtures.bankAccount(s, "Origin", 0);
        UUID destination = fixtures.bankAccount(s, "Savings", 0);
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("kind", "TRANSFER");
        transfer.put("amountCents", 500);
        transfer.put("bankAccountId", origin);
        transfer.put("destinationBankAccountId", destination);
        transfer.put("frequency", "WEEKLY");
        transfer.put("startDate", "2027-04-01");
        create(s, transfer);

        for (UUID bank : List.of(origin, destination)) {
            var result = send(HttpMethod.POST, s, "/api/v1/bank-accounts/" + bank + "/archive", null);
            assertThat(result).hasStatus(HttpStatus.CONFLICT);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IN_USE_BY_ACTIVE_RULE");
        }
    }

    @Test
    void archive_usedOnlyByFinishedRule_isAllowed() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        Map<String, Object> request = monthlyExpense(category, "2027-01-31");
        request.put("bankAccountId", bank);
        request.put("endDate", "2027-02-28");
        create(s, request);

        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null))
                .hasStatus(HttpStatus.OK);
        assertThat(send(HttpMethod.POST, s, "/api/v1/bank-accounts/" + bank + "/archive", null))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void update_revivingFinishedRuleWithArchivedReference_returns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        Map<String, Object> request = monthlyExpense(category, "2027-01-15");
        request.put("bankAccountId", bank);
        request.put("endDate", "2027-02-15");
        UUID ruleId = create(s, request);
        assertThat(jdbc.queryForObject(
                        "SELECT next_run_date FROM recurring_rules WHERE id = ?", LocalDate.class, ruleId))
                .isEqualTo(LocalDate.parse("2027-04-15"));
        // Finished, so the archive guard lets both go.
        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null))
                .hasStatus(HttpStatus.OK);

        Map<String, Object> clearEnd = updateOf(request);
        clearEnd.put("endDate", null);
        var result = send(HttpMethod.PUT, s, BASE + "/" + ruleId, clearEnd);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT end_date FROM recurring_rules WHERE id = ?", LocalDate.class, ruleId))
                .isEqualTo(LocalDate.parse("2027-02-15"));

        // A finished rule that stays finished may still be edited with its archived references.
        Map<String, Object> stillFinished = updateOf(request);
        stillFinished.put("amountCents", 2000);
        assertThat(send(HttpMethod.PUT, s, BASE + "/" + ruleId, stillFinished)).hasStatus(HttpStatus.OK);
    }

    @Test
    void transferRule_validatesLikeTransactions() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("kind", "TRANSFER");
        transfer.put("amountCents", 500);
        transfer.put("bankAccountId", a);
        transfer.put("destinationBankAccountId", a);
        transfer.put("frequency", "WEEKLY");
        transfer.put("startDate", "2027-03-01");

        var sameAccounts = send(HttpMethod.POST, s, BASE, transfer);
        assertThat(sameAccounts).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(sameAccounts).bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_INVALID");

        transfer.put("destinationBankAccountId", b);
        transfer.put("categoryId", seeded(s, "EXPENSE"));
        var withCategory = send(HttpMethod.POST, s, BASE, transfer);
        assertThat(withCategory).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(withCategory).bodyJson().extractingPath("$.code").isEqualTo("TRANSFER_INVALID");

        Map<String, Object> income = monthlyExpense(seeded(s, "EXPENSE"), "2027-03-01");
        income.put("kind", "INCOME");
        var mismatch = send(HttpMethod.POST, s, BASE, income);
        assertThat(mismatch).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mismatch).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_KIND_MISMATCH");

        income.remove("categoryId");
        var noCategory = send(HttpMethod.POST, s, BASE, income);
        assertThat(noCategory).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(noCategory).bodyJson().extractingPath("$.errors[0].field").isEqualTo("categoryId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_rules", Integer.class))
                .isZero();

        transfer.remove("categoryId");
        var created = send(HttpMethod.POST, s, BASE, transfer);
        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.bankAccount.name").isEqualTo("A");
        assertThat(created)
                .bodyJson()
                .extractingPath("$.destinationBankAccount.name")
                .isEqualTo("B");
        assertThat(created).bodyJson().extractingPath("$.category").isNull();
        UUID ruleId = UUID.fromString(body(created).path("id").asString());
        // 2027-03-01, 03-08 and 03-15 are due on 2027-03-15.
        assertThat(generatedDates(ruleId)).hasSize(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transactions WHERE recurring_rule_id = ? AND kind = 'TRANSFER'"
                                + " AND bank_account_id = ? AND destination_bank_account_id = ? AND category_id IS NULL",
                        Integer.class,
                        ruleId,
                        a,
                        b))
                .isEqualTo(3);

        // Archived bank accounts: not for new rules or switching, but a rule may keep one it already has.
        UUID c = fixtures.bankAccount(s, "C", 0);
        jdbc.update("UPDATE bank_accounts SET archived_at = now() WHERE id IN (?, ?)", b, c);
        transfer.put("destinationBankAccountId", c);
        assertThat(send(HttpMethod.POST, s, BASE, transfer))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("BANK_ACCOUNT_ARCHIVED");
        Map<String, Object> update = updateOf(transfer);
        assertThat(send(HttpMethod.PUT, s, BASE + "/" + ruleId, update))
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("BANK_ACCOUNT_ARCHIVED");
        update.put("destinationBankAccountId", b);
        update.put("amountCents", 700);
        assertThat(send(HttpMethod.PUT, s, BASE + "/" + ruleId, update)).hasStatus(HttpStatus.OK);
    }

    @Test
    void otherAccountsRule_returns404() throws Exception {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession other = testUsers.verified("bia@mail.com");
        Map<String, Object> request = monthlyExpense(seeded(owner, "EXPENSE"), "2027-01-31");
        UUID ruleId = create(owner, request);
        String uri = BASE + "/" + ruleId;

        assertThat(send(HttpMethod.GET, other, uri, null)).hasStatus(HttpStatus.NOT_FOUND);
        Map<String, Object> update = updateOf(request);
        update.put("categoryId", seeded(other, "EXPENSE"));
        assertThat(send(HttpMethod.PUT, other, uri, update)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.POST, other, uri + "/deactivate", null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.POST, other, uri + "/activate", null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, other, uri, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, other, BASE, null))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(0);

        // A rule may not reference another account's category or bank account either.
        var foreignCategory = send(HttpMethod.POST, other, BASE, request);
        assertThat(foreignCategory).hasStatus(HttpStatus.NOT_FOUND);
        Map<String, Object> foreignBank = monthlyExpense(seeded(other, "EXPENSE"), "2027-01-31");
        foreignBank.put("bankAccountId", fixtures.bankAccount(owner, "Main", 0));
        assertThat(send(HttpMethod.POST, other, BASE, foreignBank)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM recurring_rules WHERE account_id = ?", Integer.class, other.accountId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transactions WHERE account_id = ?", Integer.class, other.accountId()))
                .isZero();

        var mine = send(HttpMethod.GET, owner, uri, null);
        assertThat(mine).hasStatus(HttpStatus.OK);
        assertThat(mine).bodyJson().extractingPath("$.active").isEqualTo(true);
        assertThat(mine).bodyJson().extractingPath("$.amountCents").isEqualTo(1000);
    }

    @Test
    void list_ordersActiveFirstThenByNextRunDate() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID later = create(s, monthlyExpense(category, "2027-06-01"));
        UUID inactive = create(s, monthlyExpense(category, "2027-04-01"));
        UUID sooner = create(s, monthlyExpense(category, "2027-05-01"));
        send(HttpMethod.POST, s, BASE + "/" + inactive + "/deactivate", null);

        var result = send(HttpMethod.GET, s, BASE, null);

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode list = body(result);
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).path("id").asString()).isEqualTo(sooner.toString());
        assertThat(list.get(1).path("id").asString()).isEqualTo(later.toString());
        assertThat(list.get(2).path("id").asString()).isEqualTo(inactive.toString());
    }

    @Test
    void update_generatedTransactionToTakenDate_returns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID ruleId = create(s, monthlyExpense(category, "2027-01-31"));
        UUID february = jdbc.queryForObject(
                "SELECT id FROM transactions WHERE recurring_rule_id = ? AND date = ?",
                UUID.class,
                ruleId,
                LocalDate.parse("2027-02-28"));
        Map<String, Object> edit = new HashMap<>();
        edit.put("kind", "EXPENSE");
        edit.put("amountCents", 1000);
        edit.put("date", "2027-01-31");
        edit.put("categoryId", category);

        var result = send(HttpMethod.PUT, s, "/api/v1/transactions/" + february, edit);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("RECURRING_DATE_TAKEN");
        assertThat(generatedDates(ruleId))
                .containsExactly(LocalDate.parse("2027-01-31"), LocalDate.parse("2027-02-28"));
    }

    @Test
    void deleteResourceReferencedOnlyByRule_returns409InUse() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(s, "Gym custom", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        Map<String, Object> request = monthlyExpense(category, "2027-04-01");
        request.put("bankAccountId", bank);
        create(s, request);
        assertThat(transactionCount()).isZero();

        var bankDelete = send(HttpMethod.DELETE, s, "/api/v1/bank-accounts/" + bank, null);
        assertThat(bankDelete).hasStatus(HttpStatus.CONFLICT);
        assertThat(bankDelete).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_IN_USE");

        var categoryDelete = send(HttpMethod.DELETE, s, "/api/v1/categories/" + category, null);
        assertThat(categoryDelete).hasStatus(HttpStatus.CONFLICT);
        assertThat(categoryDelete).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_IN_USE");
    }

    @Test
    void activate_withArchivedReference_returns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = seeded(s, "EXPENSE");
        UUID bank = fixtures.bankAccount(s, "Main", 0);
        Map<String, Object> request = monthlyExpense(category, "2027-01-15");
        request.put("bankAccountId", bank);
        UUID ruleId = create(s, request);
        send(HttpMethod.POST, s, BASE + "/" + ruleId + "/deactivate", null);
        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null))
                .hasStatus(HttpStatus.OK);

        clock.setInstant(Instant.parse("2027-04-15T09:00:00Z"));
        TestSession fresh = relogin(s, "ana@mail.com");
        var result = send(HttpMethod.POST, fresh, BASE + "/" + ruleId + "/activate", null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT active FROM recurring_rules WHERE id = ?", Boolean.class, ruleId))
                .isFalse();
        assertThat(generatedDates(ruleId)).hasSize(3);

        // Same for an archived bank account once the category is usable again.
        send(HttpMethod.POST, fresh, "/api/v1/categories/" + category + "/unarchive", null);
        assertThat(send(HttpMethod.POST, fresh, "/api/v1/bank-accounts/" + bank + "/archive", null))
                .hasStatus(HttpStatus.OK);
        var bankResult = send(HttpMethod.POST, fresh, BASE + "/" + ruleId + "/activate", null);
        assertThat(bankResult).hasStatus(HttpStatus.CONFLICT);
        assertThat(bankResult).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_ARCHIVED");
        assertThat(generatedDates(ruleId)).hasSize(3);
    }

    /**
     * Runs {@code work} in its own transaction on another thread and keeps that transaction open (holding its locks)
     * until {@code release} is counted down.
     */
    private CompletableFuture<Void> holdTransaction(
            Consumer<JdbcTemplate> work, CountDownLatch acquired, CountDownLatch release) {
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            work.accept(jdbc);
            acquired.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Lock holder was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
    }

    private static void assertStillBlocked(CompletableFuture<?> future) {
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    @Test
    void createRule_whileCategoryArchiveInFlight_waitsAndReturns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(s, "Gym custom", CategoryKind.EXPENSE);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // An archive in flight: the row is locked FOR UPDATE and archived, but not committed yet.
        var archive = holdTransaction(
                j -> {
                    j.queryForObject("SELECT id FROM categories WHERE id = ? FOR UPDATE", UUID.class, category);
                    j.update("UPDATE categories SET archived_at = now() WHERE id = ?", category);
                },
                acquired,
                release);
        assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

        var createRule = CompletableFuture.supplyAsync(
                () -> send(HttpMethod.POST, s, BASE, monthlyExpense(category, "2027-04-01")));
        assertStillBlocked(createRule);
        release.countDown();
        archive.get(10, TimeUnit.SECONDS);

        var result = createRule.get(10, TimeUnit.SECONDS);
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_rules", Integer.class))
                .isZero();
    }

    @Test
    void archiveCategory_whileRuleCreateInFlight_waitsAndReturns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = fixtures.category(s, "Gym custom", CategoryKind.EXPENSE);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // A rule create in flight: the category is held FOR SHARE and an active rule is inserted, not committed yet.
        var ruleCreate = holdTransaction(
                j -> {
                    j.queryForObject("SELECT id FROM categories WHERE id = ? FOR SHARE", UUID.class, category);
                    j.update(
                            "INSERT INTO recurring_rules (id, account_id, kind, amount_cents, category_id, frequency,"
                                    + " start_date, next_run_date, active, created_by, created_at, updated_at)"
                                    + " VALUES (gen_random_uuid(), ?, 'EXPENSE', 1000, ?, 'MONTHLY', ?, ?, true, ?,"
                                    + " now(), now())",
                            s.accountId(),
                            category,
                            LocalDate.parse("2027-04-01"),
                            LocalDate.parse("2027-04-01"),
                            s.userId());
                },
                acquired,
                release);
        assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

        var archive = CompletableFuture.supplyAsync(
                () -> send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null));
        assertStillBlocked(archive);
        release.countDown();
        ruleCreate.get(10, TimeUnit.SECONDS);

        var result = archive.get(10, TimeUnit.SECONDS);
        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IN_USE_BY_ACTIVE_RULE");
        assertThat(jdbc.queryForObject("SELECT archived_at FROM categories WHERE id = ?", Instant.class, category))
                .isNull();
    }

    @Test
    void ruleWrites_waitForGeneratorLock_andKeepItsNextRunDate() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        Map<String, Object> request = monthlyExpense(seeded(s, "EXPENSE"), "2027-01-31");
        UUID ruleId = create(s, request);
        Map<String, Object> update = updateOf(request);
        update.put("amountCents", 2000);

        for (String action : List.of("update", "deactivate")) {
            CountDownLatch acquired = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            LocalDate advanced =
                    action.equals("update") ? LocalDate.parse("2027-04-30") : LocalDate.parse("2027-05-31");
            // A generator run in flight: the rule row is locked and nextRunDate moved, not committed yet.
            var generatorRun = holdTransaction(
                    j -> {
                        j.queryForObject("SELECT id FROM recurring_rules WHERE id = ? FOR UPDATE", UUID.class, ruleId);
                        j.update("UPDATE recurring_rules SET next_run_date = ? WHERE id = ?", advanced, ruleId);
                    },
                    acquired,
                    release);
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

            var write = CompletableFuture.supplyAsync(() -> action.equals("update")
                    ? send(HttpMethod.PUT, s, BASE + "/" + ruleId, update)
                    : send(HttpMethod.POST, s, BASE + "/" + ruleId + "/deactivate", null));
            assertStillBlocked(write);
            release.countDown();
            generatorRun.get(10, TimeUnit.SECONDS);

            var result = write.get(10, TimeUnit.SECONDS);
            assertThat(result).hasStatus(HttpStatus.OK);
            assertThat(result).bodyJson().extractingPath("$.nextRunDate").isEqualTo(advanced.toString());
            assertThat(jdbc.queryForObject(
                            "SELECT next_run_date FROM recurring_rules WHERE id = ?", LocalDate.class, ruleId))
                    .isEqualTo(advanced);
        }
    }
}
