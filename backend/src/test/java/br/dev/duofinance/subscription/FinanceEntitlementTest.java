package br.dev.duofinance.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class FinanceEntitlementTest {

    private static final String[] PREFIXES = {
        "/api/v1/bank-accounts",
        "/api/v1/categories",
        "/api/v1/transactions",
        "/api/v1/recurring-rules",
        "/api/v1/budgets",
        "/api/v1/dashboard"
    };

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/bank-accounts",
                "/api/v1/categories",
                "/api/v1/transactions",
                "/api/v1/recurring-rules",
                "/api/v1/budgets",
                "/api/v1/dashboard"
            })
    void inactiveSubscription_returns402(String path) {
        TestSession s = testUsers.verified("ana@mail.com");
        jdbc.update("UPDATE subscriptions SET status = 'EXPIRED' WHERE account_id = ?", s.accountId());

        var result = mvc.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.PAYMENT_REQUIRED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SUBSCRIPTION_INACTIVE");
    }

    @Test
    void trialing_hasAccess() {
        TestSession s = testUsers.verified("ana@mail.com");
        jdbc.update("UPDATE subscriptions SET status = 'TRIALING' WHERE account_id = ?", s.accountId());

        for (String path : PREFIXES) {
            var result = mvc.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                    .exchange();
            assertThat(result).as(path).hasStatus(HttpStatus.OK);
        }
    }
}
