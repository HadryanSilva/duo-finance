package br.dev.duofinance.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.service.CategoryService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

@IntegrationTest
class CategoryControllerTest {

    private static final String BASE = "/api/v1/categories";

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

    @Autowired
    CategoryService service;

    private static String body(String name, String kind) {
        return "{\"name\":\"%s\",\"kind\":\"%s\",\"color\":\"#AABBCC\",\"icon\":\"shopping-cart\"}"
                .formatted(name, kind);
    }

    private static String update(String name, String color, String icon) {
        return "{\"name\":\"%s\",\"color\":\"%s\",\"icon\":\"%s\"}".formatted(name, color, icon);
    }

    private MvcTestResult send(String method, TestSession s, String uri, String content) {
        var builder =
                mvc.method(HttpMethod.valueOf(method)).uri(uri).header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s));
        if (content != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return builder.exchange();
    }

    private Instant updatedAt(UUID id) {
        return jdbc.queryForObject("SELECT updated_at FROM categories WHERE id = ?", Timestamp.class, id)
                .toInstant();
    }

    @Test
    void list_returnsSeededDefaultsForNewUser() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = send("GET", s, BASE, null);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.length()").isEqualTo(13);
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.kind=='EXPENSE')]")
                .asList()
                .hasSize(9);
        assertThat(result)
                .bodyJson()
                .extractingPath("$[?(@.kind=='INCOME')]")
                .asList()
                .hasSize(4);
        assertThat(send("GET", s, BASE + "?kind=INCOME", null))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(4);
    }

    @Test
    void list_orderedByKindThenNameIgnoringCase_andHidesArchived() {
        TestSession s = testUsers.verified("ana@mail.com");
        fixtures.category(s, "zeta", CategoryKind.EXPENSE);
        fixtures.category(s, "alfa", CategoryKind.EXPENSE);
        UUID gone = fixtures.category(s, "Hidden", CategoryKind.INCOME);
        send("POST", s, BASE + "/" + gone + "/archive", null);

        var expenseNames = jdbc.queryForList(
                "SELECT name FROM categories WHERE account_id = ? AND kind = 'EXPENSE' ORDER BY lower(name)",
                String.class,
                s.accountId());
        assertThat(send("GET", s, BASE + "?kind=EXPENSE", null))
                .bodyJson()
                .extractingPath("$[*].name")
                .asList()
                .containsExactlyElementsOf(expenseNames);
        assertThat(send("GET", s, BASE + "?kind=INCOME", null))
                .bodyJson()
                .extractingPath("$[*].name")
                .asList()
                .doesNotContain("Hidden");
        assertThat(send("GET", s, BASE + "?kind=INCOME&includeArchived=true", null))
                .bodyJson()
                .extractingPath("$[*].name")
                .asList()
                .contains("Hidden");
        assertThat(send("GET", s, BASE, null))
                .bodyJson()
                .extractingPath("$[0].kind")
                .isEqualTo("EXPENSE");
        assertThat(send("GET", s, BASE, null))
                .bodyJson()
                .extractingPath("$[12].kind")
                .isEqualTo("INCOME");
    }

    @Test
    void create_returns201_trimmedAndNotSystemDefault() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = send("POST", s, BASE, body("  Pets  ", "EXPENSE"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Pets");
        assertThat(result).bodyJson().extractingPath("$.systemDefault").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.archived").isEqualTo(false);
    }

    @Test
    void create_duplicateNameIgnoringCase_returns409() {
        TestSession s = testUsers.verified("ana@mail.com");
        String existing = jdbc.queryForObject(
                "SELECT name FROM categories WHERE account_id = ? AND kind = 'EXPENSE' LIMIT 1",
                String.class,
                s.accountId());

        var upper = send("POST", s, BASE, body(" " + existing.toUpperCase() + " ", "EXPENSE"));
        var lower = send("POST", s, BASE, body(existing.toLowerCase(), "EXPENSE"));

        assertThat(upper).hasStatus(HttpStatus.CONFLICT);
        assertThat(upper).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NAME_TAKEN");
        assertThat(lower).hasStatus(HttpStatus.CONFLICT);
        assertThat(lower).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NAME_TAKEN");
    }

    @Test
    void create_sameNameOtherKind_isAllowed() {
        TestSession s = testUsers.verified("ana@mail.com");
        fixtures.category(s, "Shared", CategoryKind.EXPENSE);

        assertThat(send("POST", s, BASE, body("shared", "INCOME"))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void create_sameNameInOtherAccount_isAllowed() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        fixtures.category(ana, "Shared", CategoryKind.EXPENSE);

        assertThat(send("POST", bob, BASE, body("Shared", "EXPENSE"))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void update_renameToTakenName_returns409_andCaseChangeOfOwnNameIsAllowed() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.category(s, "Alpha", CategoryKind.EXPENSE);
        fixtures.category(s, "Beta", CategoryKind.EXPENSE);

        var taken = send("PUT", s, BASE + "/" + a, update("BETA", "#112233", "tag"));

        assertThat(taken).hasStatus(HttpStatus.CONFLICT);
        assertThat(taken).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NAME_TAKEN");
        assertThat(send("PUT", s, BASE + "/" + a, update("ALPHA", "#112233", "tag")))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void update_keepsKind_andChangesUpdatedAt() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.category(s, "Pets", CategoryKind.EXPENSE);
        Instant before = updatedAt(id);
        ((MutableClock) clock).advance(Duration.ofMinutes(2));

        var result = send("PUT", s, BASE + "/" + id, update(" Animals ", "#FFFFFF", "paw-print"));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Animals");
        assertThat(result).bodyJson().extractingPath("$.color").isEqualTo("#FFFFFF");
        assertThat(result).bodyJson().extractingPath("$.icon").isEqualTo("paw-print");
        assertThat(result).bodyJson().extractingPath("$.kind").isEqualTo("EXPENSE");
        assertThat(updatedAt(id)).isAfter(before);
    }

    @Test
    void archiveAndUnarchive_returnBodyAndTouchUpdatedAt() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.category(s, "Pets", CategoryKind.EXPENSE);
        Instant created = updatedAt(id);
        ((MutableClock) clock).advance(Duration.ofMinutes(2));

        var archived = send("POST", s, BASE + "/" + id + "/archive", null);
        assertThat(archived).hasStatus(HttpStatus.OK);
        assertThat(archived).bodyJson().extractingPath("$.archived").isEqualTo(true);
        Instant afterArchive = updatedAt(id);
        assertThat(afterArchive).isAfter(created);
        ((MutableClock) clock).advance(Duration.ofMinutes(2));

        var restored = send("POST", s, BASE + "/" + id + "/unarchive", null);
        assertThat(restored).hasStatus(HttpStatus.OK);
        assertThat(restored).bodyJson().extractingPath("$.archived").isEqualTo(false);
        assertThat(updatedAt(id)).isAfter(afterArchive);
    }

    @Test
    void seededDefault_canBeArchived() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND system_default LIMIT 1", UUID.class, s.accountId());

        assertThat(send("POST", s, BASE + "/" + id + "/archive", null))
                .bodyJson()
                .extractingPath("$.archived")
                .isEqualTo(true);
    }

    @Test
    void unarchive_whenNameTaken_returns409() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID old = fixtures.category(s, "Pets", CategoryKind.EXPENSE);
        send("POST", s, BASE + "/" + old + "/archive", null);
        assertThat(send("POST", s, BASE, body("pets", "EXPENSE"))).hasStatus(HttpStatus.CREATED);

        var result = send("POST", s, BASE + "/" + old + "/unarchive", null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NAME_TAKEN");
    }

    @Test
    void delete_unused_returns204_andReferenced_returns409() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID unused = fixtures.category(s, "Unused", CategoryKind.EXPENSE);
        UUID used = fixtures.category(s, "Used", CategoryKind.EXPENSE);
        jdbc.update("""
                INSERT INTO transactions (id, account_id, kind, amount_cents, date, category_id, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 100, ?, ?, now(), now())
                """, UUID.randomUUID(), s.accountId(), Date.valueOf("2026-01-01"), used);

        assertThat(send("DELETE", s, BASE + "/" + unused, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send("GET", s, BASE + "/" + unused, null)).hasStatus(HttpStatus.NOT_FOUND);
        var inUse = send("DELETE", s, BASE + "/" + used, null);
        assertThat(inUse).hasStatus(HttpStatus.CONFLICT);
        assertThat(inUse).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_IN_USE");
        assertThat(send("GET", s, BASE + "/" + used, null)).hasStatus(HttpStatus.OK);
    }

    @Test
    void delete_referencedByRecurringRule_returns409() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID used = fixtures.category(s, "Used", CategoryKind.EXPENSE);
        jdbc.update(
                """
                INSERT INTO recurring_rules (id, account_id, kind, amount_cents, category_id, frequency, start_date, next_run_date,
                    created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 100, ?, 'MONTHLY', ?, ?, now(), now())
                """, UUID.randomUUID(), s.accountId(), used, Date.valueOf("2026-01-01"), Date.valueOf("2026-01-01"));

        var result = send("DELETE", s, BASE + "/" + used, null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_IN_USE");
    }

    @Test
    void delete_cascadesBudget() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.category(s, "Budgeted", CategoryKind.EXPENSE);
        jdbc.update("""
                INSERT INTO budgets (id, account_id, category_id, monthly_limit_cents, created_at, updated_at)
                VALUES (?, ?, ?, 1000, now(), now())
                """, UUID.randomUUID(), s.accountId(), id);

        assertThat(send("DELETE", s, BASE + "/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budgets WHERE category_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void invalidColorOrIcon_returns400WithFieldErrors() {
        TestSession s = testUsers.verified("ana@mail.com");

        var bad =
                send("POST", s, BASE, "{\"name\":\"X\",\"kind\":\"EXPENSE\",\"color\":\"red\",\"icon\":\"Bad Icon\"}");

        assertThat(bad).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(bad).bodyJson().extractingPath("$.errors[*].field").asList().contains("color", "icon");
        assertThat(send("POST", s, BASE, "{\"name\":\"X\",\"kind\":\"EXPENSE\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validation_nameLimitsApplyAfterTrim() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID id = fixtures.category(s, "Pets", CategoryKind.EXPENSE);

        assertThat(send("POST", s, BASE, body("   ", "EXPENSE"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, body("x".repeat(61), "EXPENSE"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, body("  " + "x".repeat(61) + "  ", "EXPENSE")))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send("POST", s, BASE, body("  " + "x".repeat(60) + "  ", "EXPENSE")))
                .hasStatus(HttpStatus.CREATED);
        assertThat(send("PUT", s, BASE + "/" + id, update("  ", "#112233", "tag")))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void otherAccountsCategory_returns404() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        UUID id = fixtures.category(ana, "Mine", CategoryKind.EXPENSE);
        String uri = BASE + "/" + id;

        assertThat(send("GET", bob, uri, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("PUT", bob, uri, update("X", "#112233", "tag"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("DELETE", bob, uri, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("POST", bob, uri + "/archive", null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send("POST", bob, uri + "/unarchive", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void requireUsable_checksOwnershipThenKindThenArchived() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        UUID id = fixtures.category(ana, "Mine", CategoryKind.EXPENSE);

        assertThat(service.requireUsable(ana.accountId(), id, CategoryKind.EXPENSE)
                        .getId())
                .isEqualTo(id);
        assertThatThrownBy(() -> service.requireUsable(bob.accountId(), id, CategoryKind.INCOME))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        send("POST", ana, BASE + "/" + id + "/archive", null);
        assertThatThrownBy(() -> service.requireUsable(ana.accountId(), id, CategoryKind.INCOME))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CATEGORY_KIND_MISMATCH));
        assertThatThrownBy(() -> service.requireUsable(ana.accountId(), id, CategoryKind.EXPENSE))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CATEGORY_ARCHIVED));
    }
}
