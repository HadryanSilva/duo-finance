package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.TestGoogleTokens;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class GoogleLoginTest {

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
    void google_sameSubjectAgainAfterLinking_authenticates() {
        testUsers.verified("ana@mail.com");
        google(tokens.idToken("g-1", "ana@mail.com", true));

        var result = google(tokens.idToken("g-1", "ana@mail.com", true));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
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
