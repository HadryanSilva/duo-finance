package br.dev.duofinance.goal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
class GoalControllerTest {

    private static final String GOALS = "/api/v1/goals";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

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

    private static Map<String, Object> goal(String name, long target, int percent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("targetCents", target);
        m.put("monthlyPercent", percent);
        return m;
    }

    private MvcTestResult create(TestSession s, String name, int percent) {
        return send(HttpMethod.POST, s, GOALS, goal(name, 50000, percent));
    }

    private String createId(TestSession s, String name, int percent) throws Exception {
        MvcTestResult r = create(s, name, percent);
        assertThat(r).hasStatus(HttpStatus.CREATED);
        return body(r).path("id").asString();
    }

    private JsonNode overview(TestSession s) throws Exception {
        return body(send(HttpMethod.GET, s, GOALS, null));
    }

    private void assertError(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(body(result).path("code").asString()).isEqualTo(code);
    }

    @Test
    void create_returnsActiveGoalWithZeroSaved() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        MvcTestResult created = create(s, "Videogame novo", 5);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        JsonNode g = body(created);
        assertThat(g.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(g.path("savedCents").asLong()).isZero();
        assertThat(g.path("remainingCents").asLong()).isEqualTo(50000);

        JsonNode view = overview(s);
        assertThat(view.path("goals")).hasSize(1);
        assertThat(view.path("goals").get(0).path("name").asString()).isEqualTo("Videogame novo");
        assertThat(view.path("totalSavedCents").asLong()).isZero();
        assertThat(view.path("month").asString()).isEqualTo("2027-03");
    }

    @Test
    void create_duplicateActiveName_conflict() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        createId(s, "Videogame novo", 5);

        assertError(create(s, "videogame NOVO", 5), HttpStatus.CONFLICT, "GOAL_NAME_TAKEN");
    }

    @Test
    void create_percentOver100_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        createId(s, "A", 60);
        createId(s, "B", 40);

        assertError(create(s, "C", 1), HttpStatus.BAD_REQUEST, "GOAL_PERCENT_CAP");
        assertThat(create(s, "C", 0)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void update_ownPercentDoesNotCountTwice() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String id = createId(s, "A", 60);

        MvcTestResult updated = send(HttpMethod.PUT, s, GOALS + "/" + id, goal("A", 70000, 100));

        assertThat(updated).hasStatus(HttpStatus.OK);
        assertThat(body(updated).path("monthlyPercent").asInt()).isEqualTo(100);
        assertThat(body(updated).path("targetCents").asLong()).isEqualTo(70000);
    }

    @Test
    void complete_movesToHistory_andReopenRestores() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String id = createId(s, "A", 10);

        MvcTestResult done = send(HttpMethod.POST, s, GOALS + "/" + id + "/complete", null);
        assertThat(done).hasStatus(HttpStatus.OK);
        assertThat(body(done).path("status").asString()).isEqualTo("COMPLETED");
        JsonNode view = overview(s);
        assertThat(view.path("goals")).isEmpty();
        assertThat(view.path("completed")).hasSize(1);

        assertError(
                send(HttpMethod.PUT, s, GOALS + "/" + id, goal("A", 50000, 10)),
                HttpStatus.CONFLICT,
                "GOAL_COMPLETED");
        assertError(
                send(HttpMethod.POST, s, GOALS + "/" + id + "/complete", null), HttpStatus.CONFLICT, "GOAL_COMPLETED");

        MvcTestResult reopened = send(HttpMethod.POST, s, GOALS + "/" + id + "/reopen", null);
        assertThat(reopened).hasStatus(HttpStatus.OK);
        assertThat(body(reopened).path("status").asString()).isEqualTo("ACTIVE");
        view = overview(s);
        assertThat(view.path("goals")).hasSize(1);
        assertThat(view.path("completed")).isEmpty();

        assertError(
                send(HttpMethod.POST, s, GOALS + "/" + id + "/reopen", null), HttpStatus.CONFLICT, "GOAL_COMPLETED");
    }

    @Test
    void reopen_overCap_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String first = createId(s, "A", 50);
        assertThat(send(HttpMethod.POST, s, GOALS + "/" + first + "/complete", null))
                .hasStatus(HttpStatus.OK);
        createId(s, "B", 60);

        assertError(
                send(HttpMethod.POST, s, GOALS + "/" + first + "/reopen", null),
                HttpStatus.BAD_REQUEST,
                "GOAL_PERCENT_CAP");
        assertThat(overview(s).path("completed")).hasSize(1);
    }

    @Test
    void reopen_nameTakenMeanwhile_conflict() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String first = createId(s, "Reserva", 10);
        assertThat(send(HttpMethod.POST, s, GOALS + "/" + first + "/complete", null))
                .hasStatus(HttpStatus.OK);
        createId(s, "Reserva", 10);

        assertError(
                send(HttpMethod.POST, s, GOALS + "/" + first + "/reopen", null),
                HttpStatus.CONFLICT,
                "GOAL_NAME_TAKEN");
    }

    @Test
    void delete_removesGoal() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String id = createId(s, "A", 10);

        assertThat(send(HttpMethod.DELETE, s, GOALS + "/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(overview(s).path("goals")).isEmpty();
    }

    @Test
    void validation_rejectsBadInput() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        assertError(send(HttpMethod.POST, s, GOALS, goal("A", 0, 10)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(send(HttpMethod.POST, s, GOALS, goal("A", 100, 101)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(send(HttpMethod.POST, s, GOALS, goal(" ", 100, 10)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }
}
