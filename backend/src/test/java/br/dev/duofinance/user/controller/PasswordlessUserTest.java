package br.dev.duofinance.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class PasswordlessUserTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private MvcTestResult me(TestSession s) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private MvcTestResult delete(TestSession s, String body) {
        return mvc.delete()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private void linkGoogle(TestSession s, String email) {
        jdbc.update(
                "INSERT INTO user_identities (id, user_id, provider, provider_subject, created_at)"
                        + " VALUES (gen_random_uuid(), ?, 'GOOGLE', ?, now())",
                s.userId(),
                "g-" + email);
    }

    @Test
    void me_googleOnly_hasNoPassword() {
        TestSession s = testUsers.google("novo@mail.com");

        var result = me(s);

        assertThat(result).bodyJson().extractingPath("$.hasPassword").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.providers").isEqualTo(java.util.List.of("GOOGLE"));
    }

    @Test
    void me_linked_hasBoth() {
        TestSession s = testUsers.verified("ana@mail.com");
        linkGoogle(s, "ana@mail.com");

        var result = me(s);

        assertThat(result).bodyJson().extractingPath("$.hasPassword").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.providers").isEqualTo(java.util.List.of("GOOGLE", "LOCAL"));
    }

    @Test
    void delete_googleOnly_withMatchingEmail_deletes() {
        TestSession s = testUsers.google("novo@mail.com");

        assertThat(delete(s, "{\"confirmEmail\":\"Novo@Mail.com\"}")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, s.userId()))
                .isZero();
    }

    @Test
    void delete_googleOnly_withWrongEmail_is401() {
        TestSession s = testUsers.google("novo@mail.com");

        var result = delete(s, "{\"confirmEmail\":\"outro@mail.com\"}");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void delete_withPassword_stillRequiresPassword() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = delete(s, "{\"confirmEmail\":\"ana@mail.com\"}");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void changePassword_googleOnly_isInvalidCredentials() {
        TestSession s = testUsers.google("novo@mail.com");

        var result = mvc.put()
                .uri("/api/v1/me/password")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"whatever-pass\",\"newPassword\":\"brand-new-pass\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void reset_googleOnly_createsLocalIdentity() {
        testUsers.google("novo@mail.com");
        emails.clear();

        assertThat(mvc.post()
                        .uri("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"novo@mail.com\"}")
                        .exchange())
                .hasStatus(HttpStatus.ACCEPTED);
        String link = emails.awaitLink("novo@mail.com", "/reset-password");
        String token = link.substring(link.indexOf("token=") + 6);
        assertThat(mvc.post()
                        .uri("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\",\"newPassword\":\"brand-new-pass\"}".formatted(token))
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.post()
                        .uri("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"novo@mail.com\",\"password\":\"brand-new-pass\"}")
                        .exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void forgot_unknownEmail_staysSilent() {
        assertThat(mvc.post()
                        .uri("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@mail.com\"}")
                        .exchange())
                .hasStatus(HttpStatus.ACCEPTED);

        assertThat(emails.lastTo("nobody@mail.com")).isNull();
    }
}
