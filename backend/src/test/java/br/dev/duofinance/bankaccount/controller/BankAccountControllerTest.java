package br.dev.duofinance.bankaccount.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class BankAccountControllerTest {

    private static final String BASE = "/api/v1/bank-accounts";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private static String body(String name, long initial, UUID holder) {
        return "{\"name\":\"%s\",\"type\":\"SAVINGS\",\"initialBalanceCents\":%d%s}"
                .formatted(name, initial, holder == null ? "" : ",\"holderUserId\":\"" + holder + "\"");
    }

    private MvcTestResult send(String method, TestSession s, String uri, String content) {
        var builder = mvc.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s));
        if (content != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return builder.exchange();
    }

    private void insertTransaction(
            TestSession s, String kind, long cents, LocalDate date, UUID bank, UUID destination) {
        UUID category = null;
        if (!kind.equals("TRANSFER")) {
            category = jdbc.queryForObject(
                    "SELECT id FROM categories WHERE account_id = ? AND kind = ? LIMIT 1",
                    UUID.class,
                    s.accountId(),
                    kind);
        }
        jdbc.update(
                """
                INSERT INTO transactions (id, account_id, kind, amount_cents, date, category_id, bank_account_id,
                    destination_bank_account_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, UUID.randomUUID(), s.accountId(), kind, cents, Date.valueOf(date), category, bank, destination);
    }

    @Test
    void create_returns201WithBalanceEqualToInitial() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = send("POST", s, BASE, body("  Main  ", -5000, null));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Main");
        assertThat(result).bodyJson().extractingPath("$.balanceCents").isEqualTo(-5000);
        assertThat(result).bodyJson().extractingPath("$.initialBalanceCents").isEqualTo(-5000);
        assertThat(result).bodyJson().extractingPath("$.archived").isEqualTo(false);
    }

    @Test
    void create_paddedSixtyCharName_isAcceptedAndStoredTrimmed() {
        TestSession s = testUsers.verified("ana@mail.com");
        String name = "x".repeat(60);

        var result = send("POST", s, BASE, body("  " + name + "  ", 0, null));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo(name);
    }

    @Test
    void list_hidesArchivedUnlessRequested() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID zeta = fixtures.bankAccount(s, "Zeta", 0);
        UUID alpha = fixtures.bankAccount(s, "Alpha", 0);
        fixtures.bankAccount(s, "Beta", 0);
        send("POST", s, BASE + "/" + alpha + "/archive", null);

        var visible = send("GET", s, BASE, null);
        var all = send("GET", s, BASE + "?includeArchived=true", null);

        assertThat(visible).bodyJson().extractingPath("$.length()").isEqualTo(2);
        assertThat(visible).bodyJson().extractingPath("$[0].name").isEqualTo("Beta");
        assertThat(visible).bodyJson().extractingPath("$[1].id").isEqualTo(zeta.toString());
        assertThat(all).bodyJson().extractingPath("$.length()").isEqualTo(3);
        assertThat(all).bodyJson().extractingPath("$[2].name").isEqualTo("Alpha");
    }

    @Test
    void balance_appliesFormulaAndIgnoresFutureDates() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID main = fixtures.bankAccount(s, "Main", 10000);
        UUID other = fixtures.bankAccount(s, "Other", 0);
        LocalDate today = LocalDate.now(clock);
        insertTransaction(s, "INCOME", 2000, today, main, null);
        insertTransaction(s, "EXPENSE", 500, today.minusDays(1), main, null);
        insertTransaction(s, "TRANSFER", 1000, today, main, other);
        insertTransaction(s, "TRANSFER", 300, today, other, main);
        insertTransaction(s, "EXPENSE", 9999, today.plusDays(1), main, null);

        var main1 = send("GET", s, BASE + "/" + main, null);
        var other1 = send("GET", s, BASE + "/" + other, null);

        assertThat(main1).bodyJson().extractingPath("$.balanceCents").isEqualTo(10800);
        assertThat(other1).bodyJson().extractingPath("$.balanceCents").isEqualTo(700);
        assertThat(send("GET", s, BASE, null))
                .bodyJson()
                .extractingPath("$[?(@.name=='Main')].balanceCents")
                .asList()
                .containsExactly(10800);
    }

    @Test
    void holderMustBeMember() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");

        assertThat(send("POST", ana, BASE, body("X", 0, bob.userId()))).hasStatus(HttpStatus.NOT_FOUND);
        var ok = send("POST", ana, BASE, body("X", 0, ana.userId()));
        assertThat(ok).hasStatus(HttpStatus.CREATED);
        assertThat(ok)
                .bodyJson()
                .extractingPath("$.holderUserId")
                .isEqualTo(ana.userId().toString());
    }

    @Test
    void update_replacesAllFields() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.bankAccount(s, "Old", 100);

        var result = send("PUT", s, BASE + "/" + id, body("New", 900, s.userId()));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("New");
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("SAVINGS");
        assertThat(result).bodyJson().extractingPath("$.balanceCents").isEqualTo(900);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.holderUserId")
                .isEqualTo(s.userId().toString());

        var cleared = send("PUT", s, BASE + "/" + id, body("New", 900, null));
        assertThat(cleared).bodyJson().extractingPath("$.holderUserId").isNull();
    }

    @Test
    void archiveAndUnarchive() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.bankAccount(s, "Main", 0);

        var archived = send("POST", s, BASE + "/" + id + "/archive", null);
        assertThat(archived).hasStatus(HttpStatus.OK);
        assertThat(archived).bodyJson().extractingPath("$.archived").isEqualTo(true);

        var restored = send("POST", s, BASE + "/" + id + "/unarchive", null);
        assertThat(restored).hasStatus(HttpStatus.OK);
        assertThat(restored).bodyJson().extractingPath("$.archived").isEqualTo(false);
    }

    @Test
    void delete_unused_returns204() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.bankAccount(s, "Main", 0);

        assertThat(send("DELETE", s, BASE + "/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send("GET", s, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_referencedByTransaction_returns409InUse() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.bankAccount(s, "Main", 0);
        insertTransaction(s, "INCOME", 100, LocalDate.now(clock), id, null);

        var result = send("DELETE", s, BASE + "/" + id, null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_IN_USE");
        assertThat(send("GET", s, BASE + "/" + id, null)).hasStatus(HttpStatus.OK);
    }

    @Test
    void otherAccountsBankAccount_returns404OnGetPutDeleteArchive() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        UUID id = fixtures.bankAccount(ana, "Main", 0);
        String uri = BASE + "/" + id;

        assertThat(send("GET", bob, uri, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("PUT", bob, uri, body("X", 0, null))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("DELETE", bob, uri, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("POST", bob, uri + "/archive", null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("POST", bob, uri + "/unarchive", null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("GET", bob, BASE + "/" + UUID.randomUUID(), null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("GET", bob, BASE, null))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    void validation_nameBlankOrTooLong_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");

        assertThat(send("POST", s, BASE, body("   ", 0, null))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, body("x".repeat(61), 0, null))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, body("x".repeat(60), 0, null))).hasStatus(HttpStatus.CREATED);
        assertThat(send("POST", s, BASE, body("  " + "x".repeat(61) + "  ", 0, null)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, body("Y", 100_000_000_001L, null))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, "{\"name\":\"Z\",\"type\":\"CASH\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
