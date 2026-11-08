package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class RefreshLogoutTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AuthSessionService sessions;

    @Autowired
    UserRepository users;

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountMemberRepository members;

    @Autowired
    MutableClock clock;

    String raw1;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now(clock);
        User user = users.saveAndFlush(new User("ana@mail.com", "Ana", "pt-BR", "2026-11", now));
        Account account = accounts.saveAndFlush(new Account("Casa", now));
        members.saveAndFlush(new AccountMember(account.getId(), user.getId(), now));
        raw1 = sessions.start(user).rawRefreshToken();
    }

    @Test
    void refreshEndpoint_readsCookieAndSetsRotatedCookie() {
        var result = mvc.post()
                .uri("/api/v1/auth/refresh")
                .cookie(new Cookie("df_refresh", raw1))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result)
                .bodyJson()
                .satisfies(json ->
                        json.assertThat().extractingPath("$.accessToken").isNotNull());
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("df_refresh=")
                .doesNotContain("df_refresh=" + raw1)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");
    }

    @Test
    void refreshEndpoint_withoutCookie_returns401() {
        assertThat(mvc.post().uri("/api/v1/auth/refresh")).hasStatus(401);
    }

    @Test
    void logout_revokesFamilyAndClearsCookie() {
        var result = mvc.post()
                .uri("/api/v1/auth/logout")
                .cookie(new Cookie("df_refresh", raw1))
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
        assertThat(mvc.post()
                        .uri("/api/v1/auth/refresh")
                        .cookie(new Cookie("df_refresh", raw1))
                        .exchange())
                .hasStatus(401);
    }

    @Test
    void logout_withoutCookie_stillReturns204() {
        var result = mvc.post().uri("/api/v1/auth/logout").exchange();

        assertThat(result).hasStatus(204);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }
}
