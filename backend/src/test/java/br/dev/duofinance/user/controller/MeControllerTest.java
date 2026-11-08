package br.dev.duofinance.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class MeControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    private org.springframework.test.web.servlet.assertj.MvcTestResult patch(TestSession s, String body) {
        return mvc.patch()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult changePassword(
            TestSession s, String current, String next) {
        return mvc.put()
                .uri("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next))
                .exchange();
    }

    @Test
    void getMe_returnsProfile() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("ana@mail.com");
        assertThat(result).bodyJson().extractingPath("$.emailVerified").isEqualTo(true);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.accountId")
                .isEqualTo(s.accountId().toString());
        assertThat(result).bodyJson().extractingPath("$.locale").isEqualTo("pt-BR");
    }

    @Test
    void getMe_withoutToken_returns401() {
        assertThat(mvc.get().uri("/api/v1/me").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void patchMe_updatesLocaleAndRejectsUnknownLocale() {
        TestSession s = testUsers.registered("ana@mail.com");

        var ok = patch(s, "{\"locale\":\"en\",\"displayName\":\"  Ana B  \"}");
        assertThat(ok).hasStatus(HttpStatus.OK);
        assertThat(ok).bodyJson().extractingPath("$.locale").isEqualTo("en");
        assertThat(ok).bodyJson().extractingPath("$.displayName").isEqualTo("Ana B");

        var bad = patch(s, "{\"locale\":\"es\"}");
        assertThat(bad).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(bad).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");

        assertThat(patch(s, "{\"displayName\":\"   \"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(patch(s, "{\"displayName\":\"" + "x".repeat(61) + "\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchMe_omittedFieldsAreUnchanged() {
        TestSession s = testUsers.registered("ana@mail.com");

        var result = patch(s, "{}");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Test User");
        assertThat(result).bodyJson().extractingPath("$.locale").isEqualTo("pt-BR");
    }

    @Test
    void changePassword_wrongCurrent_returns401() {
        TestSession s = testUsers.registered("ana@mail.com");

        var result = changePassword(s, "not-the-password", "brand-new-pass");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        // the old password still works
        assertThat(mvc.post()
                        .uri("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@mail.com\",\"password\":\"%s\"}".formatted(TestUsers.PASSWORD))
                        .exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void changePassword_shortNewPassword_returns400() {
        TestSession s = testUsers.registered("ana@mail.com");

        var result = changePassword(s, TestUsers.PASSWORD, "short");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void changePassword_revokesOldRefreshAndReturnsNewSession() {
        TestSession s = testUsers.registered("ana@mail.com");

        var result = changePassword(s, TestUsers.PASSWORD, "brand-new-pass");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.accessToken").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.user.email").isEqualTo("ana@mail.com");
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("df_refresh=");
        String newRefresh = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(newRefresh).isNotEqualTo(s.refreshCookie());

        // old refresh token is revoked
        assertThat(mvc.post()
                        .uri("/api/v1/auth/refresh")
                        .cookie(new Cookie("df_refresh", s.refreshCookie()))
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        // new one works
        assertThat(mvc.post()
                        .uri("/api/v1/auth/refresh")
                        .cookie(new Cookie("df_refresh", newRefresh))
                        .exchange())
                .hasStatus(HttpStatus.OK);
        // login uses the new password only
        assertThat(login("ana@mail.com", "brand-new-pass")).hasStatus(HttpStatus.OK);
        assertThat(login("ana@mail.com", TestUsers.PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult login(String email, String password) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }
}
