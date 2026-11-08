package br.dev.duofinance.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class EntitlementTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    private MvcTestResult get(String uri, TestSession s) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private void setStatus(UUID accountId, String status) {
        jdbc.update("UPDATE subscriptions SET status = ? WHERE account_id = ?", status, accountId);
    }

    @Test
    void activeBeta_accessesFinancialRoute() {
        TestSession s = testUsers.verified("ana@mail.com");

        assertThat(get("/api/v1/transactions/__probe", s)).hasStatus(HttpStatus.OK);
    }

    @Test
    void inactive_getsPaymentRequiredOnFinancialRoutes() {
        TestSession s = testUsers.verified("ana@mail.com");
        setStatus(s.accountId(), "EXPIRED");

        var result = get("/api/v1/transactions/__probe", s);

        assertThat(result).hasStatus(HttpStatus.PAYMENT_REQUIRED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SUBSCRIPTION_INACTIVE");
    }

    @Test
    void missingSubscription_getsPaymentRequired() {
        TestSession s = testUsers.verified("ana@mail.com");
        jdbc.update("DELETE FROM subscriptions WHERE account_id = ?", s.accountId());

        assertThat(get("/api/v1/transactions/__probe", s)).hasStatus(HttpStatus.PAYMENT_REQUIRED);
    }

    @Test
    void inactive_canStillUseMeAccountAndSubscription() {
        TestSession s = testUsers.verified("ana@mail.com");
        setStatus(s.accountId(), "EXPIRED");

        assertThat(get("/api/v1/me", s)).hasStatus(HttpStatus.OK);
        assertThat(get("/api/v1/account", s)).hasStatus(HttpStatus.OK);
        assertThat(get("/api/v1/account/subscription", s)).hasStatus(HttpStatus.OK);
    }

    @Test
    void unauthenticatedFinancialRoute_isUnauthorizedNotPaymentRequired() {
        assertThat(mvc.get().uri("/api/v1/transactions/__probe").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void staleToken_isRejectedBeforeEntitlement() {
        TestSession s = testUsers.verified("ana@mail.com");
        setStatus(s.accountId(), "EXPIRED");
        jdbc.update("DELETE FROM account_members WHERE user_id = ?", s.userId());

        var result = get("/api/v1/transactions/__probe", s);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SESSION_STALE");
    }

    @Test
    void subscriptionEndpoint_reportsBeta() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = get("/api/v1/account/subscription", s);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.plan").isEqualTo("BETA");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(result).bodyJson().extractingPath("$.hasAccess").isEqualTo(true);
    }

    @Test
    void subscriptionEndpoint_reportsNoAccessWhenExpired() {
        TestSession s = testUsers.verified("ana@mail.com");
        setStatus(s.accountId(), "EXPIRED");

        var result = get("/api/v1/account/subscription", s);

        assertThat(result).bodyJson().extractingPath("$.hasAccess").isEqualTo(false);
    }
}
