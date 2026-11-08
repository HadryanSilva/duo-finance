package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class PasswordResetTest {

    private static final String NEW_PASSWORD = "brand-new-pass";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    UserRepository users;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private MvcTestResult forgot(String email) {
        return mvc.post()
                .uri("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))
                .exchange();
    }

    private String tokenSentTo(String email, String path) {
        Awaitility.await().untilAsserted(() -> {
            assertThat(emails.lastTo(email)).isNotNull();
            assertThat(emails.extractLink(emails.lastTo(email), path)).isNotNull();
        });
        String link = emails.extractLink(emails.lastTo(email), path);
        return link.substring(link.indexOf("token=") + 6);
    }

    private MvcTestResult reset(String token, String password) {
        return mvc.post()
                .uri("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, password))
                .exchange();
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .exchange();
    }

    /** Registers the user and discards the verification e-mail so only reset e-mails remain. */
    private TestUsers.TestSession registerAndDrainMail(String email) {
        var session = testUsers.registered(email);
        tokenSentTo(email, "/verify-email");
        emails.clear();
        return session;
    }

    @Test
    void forgot_unknownEmail_returns202AndSendsNothing() throws Exception {
        assertThat(forgot("nobody@mail.com")).hasStatus(202);

        Thread.sleep(300);
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void forgot_knownEmail_returns202AndSendsLinkNormalizingEmail() {
        registerAndDrainMail("ana@mail.com");

        assertThat(forgot(" ANA@mail.com ")).hasStatus(202);

        assertThat(tokenSentTo("ana@mail.com", "/reset-password")).isNotBlank();
    }

    @Test
    void reset_changesPasswordAndRevokesSessions() {
        var session = registerAndDrainMail("ana@mail.com");
        forgot("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/reset-password");

        assertThat(reset(token, NEW_PASSWORD)).hasStatus(204);

        var refresh = mvc.post()
                .uri("/api/v1/auth/refresh")
                .cookie(new Cookie("df_refresh", session.refreshCookie()))
                .exchange();
        assertThat(refresh).hasStatus(401);
        assertThat(login("ana@mail.com", TestUsers.PASSWORD)).hasStatus(401);
        assertThat(login("ana@mail.com", NEW_PASSWORD)).hasStatus(200);
    }

    @Test
    void reset_tokenIsSingleUse() {
        registerAndDrainMail("ana@mail.com");
        forgot("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/reset-password");

        assertThat(reset(token, NEW_PASSWORD)).hasStatus(204);
        var second = reset(token, "another-pass-1");

        assertThat(second).hasStatus(400);
        assertThat(second).bodyJson().extractingPath("$.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    void reset_newRequestInvalidatesPreviousToken() {
        registerAndDrainMail("ana@mail.com");
        forgot("ana@mail.com");
        String oldToken = tokenSentTo("ana@mail.com", "/reset-password");
        emails.clear();
        forgot("ana@mail.com");
        String newToken = tokenSentTo("ana@mail.com", "/reset-password");

        assertThat(reset(oldToken, NEW_PASSWORD)).hasStatus(400);
        assertThat(reset(newToken, NEW_PASSWORD)).hasStatus(204);
    }

    @Test
    void reset_expiresAfter1h() {
        registerAndDrainMail("ana@mail.com");
        forgot("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/reset-password");

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThat(reset(token, NEW_PASSWORD)).hasStatus(400);
        assertThat(login("ana@mail.com", TestUsers.PASSWORD)).hasStatus(200);
    }

    @Test
    void reset_weakPassword_returns400AndKeepsToken() {
        registerAndDrainMail("ana@mail.com");
        forgot("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/reset-password");

        assertThat(reset(token, "short")).hasStatus(400);
        assertThat(reset(token, NEW_PASSWORD)).hasStatus(204);
    }

    @Test
    void reset_unlocksLockedAccount() {
        var session = registerAndDrainMail("ana@mail.com");
        for (int i = 0; i < 5; i++) {
            login("ana@mail.com", "wrong-password");
        }
        assertThat(users.findById(session.userId()).orElseThrow().getLockedUntil())
                .isNotNull();
        forgot("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/reset-password");

        assertThat(reset(token, NEW_PASSWORD)).hasStatus(204);

        var user = users.findById(session.userId()).orElseThrow();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(login("ana@mail.com", NEW_PASSWORD)).hasStatus(200);
    }

    @Test
    void reset_verificationTokenCannotResetPassword() {
        testUsers.registered("ana@mail.com");
        String verifyToken = tokenSentTo("ana@mail.com", "/verify-email");

        assertThat(reset(verifyToken, NEW_PASSWORD)).hasStatus(400);
    }
}
