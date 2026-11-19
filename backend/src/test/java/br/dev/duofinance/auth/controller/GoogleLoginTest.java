package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestGoogleTokens;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class GoogleLoginTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    MutableClock clock;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    TestGoogleTokens tokens;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private MvcTestResult google(String idToken) {
        return mvc.post()
                .uri("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"%s\"}".formatted(idToken))
                .exchange();
    }

    private MvcTestResult passwordLogin(String email) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, TestUsers.PASSWORD))
                .exchange();
    }

    @Test
    void google_unknownUser_returnsSignupRequired() {
        var result = google(tokens.idToken("g-1", "novo@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("SIGNUP_REQUIRED");
        assertThat(result).bodyJson().extractingPath("$.signupTicket").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("novo@mail.com");
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Ana Google");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void google_linkedSubject_authenticates() {
        var session = testUsers.verified("ana@mail.com");
        google(tokens.idToken("g-1", "ana@mail.com", true));

        var result = google(tokens.idToken("g-1", "outro@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.user.id")
                .isEqualTo(session.userId().toString());
    }

    @Test
    void google_verifiedLocalUser_linksAndKeepsPassword() {
        testUsers.verified("ana@mail.com");

        var result = google(tokens.idToken("g-1", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("df_refresh=");
        assertThat(passwordLogin("ana@mail.com")).hasStatus(HttpStatus.OK);
    }

    @Test
    void google_unverifiedLocalUser_dropsPasswordAndSessions() {
        var session = testUsers.registered("ana@mail.com");

        var result = google(tokens.idToken("g-1", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
        assertThat(passwordLogin("ana@mail.com")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/auth/refresh").cookie(new Cookie("df_refresh", session.refreshCookie())))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(users.findById(session.userId()).orElseThrow().getEmailVerifiedAt())
                .isNotNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM user_tokens WHERE user_id = ? AND used_at IS NULL",
                        Integer.class,
                        session.userId()))
                .isZero();
    }

    @Test
    void google_unverifiedLocalUser_invalidatesEarlierAccessTokens() throws Exception {
        var attacker = testUsers.registered("ana@mail.com");
        clock.advance(Duration.ofSeconds(2));

        var result = google(tokens.idToken("g-1", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        var me = mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(attacker))
                .exchange();
        assertThat(me).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(me).bodyJson().extractingPath("$.code").isEqualTo("SESSION_STALE");
        var invite = mvc.post()
                .uri("/api/v1/account/invitations")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(attacker))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"attacker@mail.com\"}")
                .exchange();
        assertThat(invite).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(invite).bodyJson().extractingPath("$.code").isEqualTo("SESSION_STALE");
        String googleToken = json.readTree(result.getResponse().getContentAsString())
                .path("accessToken")
                .asString();
        assertThat(mvc.get().uri("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + googleToken))
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void google_linksCaseInsensitiveEmail() {
        testUsers.verified("ana@gmail.com");

        var result = google(tokens.idToken("g-1", "Ana@Gmail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
    }

    @Test
    void google_secondGoogleAccountSameEmail_isConflict() {
        testUsers.verified("ana@mail.com");
        google(tokens.idToken("g-1", "ana@mail.com", true));

        var result = google(tokens.idToken("g-2", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("GOOGLE_ACCOUNT_CONFLICT");
    }

    @Test
    void google_unverifiedEmail_is401() {
        var result = google(tokens.idToken("g-1", "ana@mail.com", false));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("GOOGLE_EMAIL_UNVERIFIED");
    }

    @Test
    void google_wrongAudience_is401() {
        var result = google(tokens.idToken(Map.of(
                "sub", "g-1", "email", "ana@mail.com", "email_verified", true, "aud", java.util.List.of("other"))));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("GOOGLE_TOKEN_INVALID");
    }

    @Test
    void google_lockedPasswordAccount_stillAuthenticates() {
        testUsers.verified("ana@mail.com");
        for (int i = 0; i < 5; i++) {
            mvc.post()
                    .uri("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ana@mail.com\",\"password\":\"wrong-password\"}")
                    .exchange();
        }
        assertThat(passwordLogin("ana@mail.com")).hasStatus(HttpStatus.UNAUTHORIZED);

        var result = google(tokens.idToken("g-1", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
    }
}
