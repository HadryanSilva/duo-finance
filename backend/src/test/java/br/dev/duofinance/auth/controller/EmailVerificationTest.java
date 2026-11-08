package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class EmailVerificationTest {

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

    private String tokenSentTo(String email, String path) {
        String link = emails.awaitLink(email, path);
        return link.substring(link.indexOf("token=") + 6);
    }

    private MvcTestResult verify(String token) {
        return mvc.post()
                .uri("/api/v1/auth/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token))
                .exchange();
    }

    @Test
    void verify_marksEmailVerified() {
        var session = testUsers.registered("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/verify-email");

        assertThat(verify(token)).hasStatus(204);

        assertThat(users.findById(session.userId()).orElseThrow().getEmailVerifiedAt())
                .isNotNull();
    }

    @Test
    void verify_tokenIsSingleUse() {
        testUsers.registered("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/verify-email");

        assertThat(verify(token)).hasStatus(204);
        var second = verify(token);

        assertThat(second).hasStatus(400);
        assertThat(second).bodyJson().extractingPath("$.code").isEqualTo("TOKEN_INVALID");
    }

    @Test
    void verify_expiresAfter24h() {
        testUsers.registered("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/verify-email");

        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(verify(token)).hasStatus(400);
    }

    @Test
    void verify_unknownToken_returns400() {
        assertThat(verify("nope")).hasStatus(400);
    }

    @Test
    void verify_resetTokenCannotVerifyEmail() {
        var session = testUsers.registered("ana@mail.com");
        emails.clear();
        mvc.post()
                .uri("/api/v1/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@mail.com\"}")
                .exchange();
        String resetToken = tokenSentTo("ana@mail.com", "/reset-password");

        assertThat(verify(resetToken)).hasStatus(400);
        assertThat(users.findById(session.userId()).orElseThrow().getEmailVerifiedAt())
                .isNull();
    }

    @Test
    void verify_alreadyVerifiedUser_spendsTokenAndReturns204() {
        var session = testUsers.registered("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/verify-email");
        Instant verifiedAt = Instant.parse("2020-01-01T00:00:00Z");
        var user = users.findById(session.userId()).orElseThrow();
        user.setEmailVerifiedAt(verifiedAt);
        users.saveAndFlush(user);

        assertThat(verify(token)).hasStatus(204);
        assertThat(verify(token)).hasStatus(400);
        assertThat(users.findById(session.userId()).orElseThrow().getEmailVerifiedAt())
                .isEqualTo(verifiedAt);
    }

    @Test
    void verify_concurrentUse_succeedsExactlyOnce() throws Exception {
        testUsers.registered("ana@mail.com");
        String token = tokenSentTo("ana@mail.com", "/verify-email");
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return verify(token).getResponse().getStatus();
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            if (f.get() == 204) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
    }

    @Test
    void resend_invalidatesPreviousToken() {
        var session = testUsers.registered("ana@mail.com");
        String oldToken = tokenSentTo("ana@mail.com", "/verify-email");
        emails.clear();

        var resend = mvc.post()
                .uri("/api/v1/auth/resend-verification")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .exchange();
        assertThat(resend).hasStatus(204);
        String newToken = tokenSentTo("ana@mail.com", "/verify-email");

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(verify(oldToken)).hasStatus(400);
        assertThat(verify(newToken)).hasStatus(204);
    }

    @Test
    void resend_alreadyVerified_sendsNothing() throws Exception {
        var session = testUsers.verified("ana@mail.com");
        Thread.sleep(300);
        emails.clear();

        var resend = mvc.post()
                .uri("/api/v1/auth/resend-verification")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .exchange();

        assertThat(resend).hasStatus(204);
        Thread.sleep(300);
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void resend_requiresAuthentication() {
        assertThat(mvc.post().uri("/api/v1/auth/resend-verification").exchange())
                .hasStatus(401);
    }
}
