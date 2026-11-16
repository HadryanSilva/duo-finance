package br.dev.duofinance.goal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class GoalSettingsControllerTest {

    private static final String SETTINGS = "/api/v1/goals/settings";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setClock() {
        clock.setInstant(Instant.parse("2027-03-15T10:00:00Z"));
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

    private MvcTestResult put(TestSession s, UUID categoryId) {
        Map<String, Object> request = new HashMap<>();
        request.put("salaryCategoryId", categoryId);
        return send(HttpMethod.PUT, s, SETTINGS, request);
    }

    @Test
    void settings_defaultsToNoSalaryCategory() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        MvcTestResult result = send(HttpMethod.GET, s, SETTINGS, null);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(body(result).path("salaryCategory").isNull()).isTrue();
    }

    @Test
    void settings_setIncomeCategory_returnsIt() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID salary = fixtures.category(s, "Paycheck", CategoryKind.INCOME);

        MvcTestResult updated = put(s, salary);

        assertThat(updated).hasStatus(HttpStatus.OK);
        JsonNode ref = body(updated).path("salaryCategory");
        assertThat(ref.path("id").asString()).isEqualTo(salary.toString());
        assertThat(ref.path("name").asString()).isEqualTo("Paycheck");
        assertThat(ref.path("archived").asBoolean()).isFalse();

        JsonNode fetched = body(send(HttpMethod.GET, s, SETTINGS, null));
        assertThat(fetched.path("salaryCategory").path("name").asString()).isEqualTo("Paycheck");
    }

    @Test
    void settings_expenseCategory_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);

        MvcTestResult result = put(s, food);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(result).path("code").asString()).isEqualTo("GOAL_SALARY_CATEGORY_INVALID");
    }

    @Test
    void settings_archivedCategory_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID salary = fixtures.category(s, "Paycheck", CategoryKind.INCOME);
        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + salary + "/archive", null))
                .hasStatus(HttpStatus.OK);

        MvcTestResult result = put(s, salary);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(result).path("code").asString()).isEqualTo("GOAL_SALARY_CATEGORY_INVALID");
    }

    @Test
    void settings_clearWithNull() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        assertThat(put(s, fixtures.category(s, "Paycheck", CategoryKind.INCOME))).hasStatus(HttpStatus.OK);

        MvcTestResult cleared = put(s, null);

        assertThat(cleared).hasStatus(HttpStatus.OK);
        assertThat(body(cleared).path("salaryCategory").isNull()).isTrue();
        assertThat(body(send(HttpMethod.GET, s, SETTINGS, null)).path("salaryCategory").isNull())
                .isTrue();
    }

    @Test
    void settings_deletingCategory_clearsIt() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID salary = fixtures.category(s, "Paycheck", CategoryKind.INCOME);
        assertThat(put(s, salary)).hasStatus(HttpStatus.OK);

        assertThat(send(HttpMethod.DELETE, s, "/api/v1/categories/" + salary, null))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(body(send(HttpMethod.GET, s, SETTINGS, null)).path("salaryCategory").isNull())
                .isTrue();
    }

    @Test
    void settings_partnerSeesSameSetting() throws Exception {
        TestSession[] couple = testUsers.couple("ana@mail.com", "bia@mail.com");
        UUID salary = fixtures.category(couple[0], "Paycheck", CategoryKind.INCOME);
        assertThat(put(couple[0], salary)).hasStatus(HttpStatus.OK);

        JsonNode seen = body(send(HttpMethod.GET, couple[1], SETTINGS, null));

        assertThat(seen.path("salaryCategory").path("id").asString()).isEqualTo(salary.toString());
    }
}
