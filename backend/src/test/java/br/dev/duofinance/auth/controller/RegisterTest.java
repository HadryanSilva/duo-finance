package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.access.service.AccessCodeService;
import br.dev.duofinance.shared.email.EmailMessage;
import br.dev.duofinance.support.RecordingEmailSender;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

@IntegrationTest
class RegisterTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AccessCodeService accessCodes;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private static String body(String email, boolean acceptTerms, String accessCode) {
        String code = accessCode == null ? "null" : "\"" + accessCode + "\"";
        return """
                {"email":"%s","password":"s3cret-pass","displayName":"Ana","locale":"pt-BR",
                 "acceptTerms":%s,"termsVersion":"2026-11","accessCode":%s}
                """.formatted(email, acceptTerms, code);
    }

    private MockMvcRequestBuilder register(String body) {
        return mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String newCode(String boundEmail) {
        return accessCodes.generate(boundEmail, 1, null, "test", null).plainCode();
    }

    @Test
    void register_withValidCode_returns201AndSession() {
        var result = register(body("ana@mail.com", true, newCode(null))).exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.accessToken").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.user.emailVerified").isEqualTo(false);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("df_refresh=");
        Awaitility.await().untilAsserted(() -> {
            EmailMessage mail = emails.lastTo("ana@mail.com");
            assertThat(mail).isNotNull();
            assertThat(emails.extractLink(mail, "/verify-email")).contains("token=");
        });
        assertThat(emails.sent()).hasSize(1);
    }

    @Test
    void register_withoutCredentialInInviteOnly_returns400AccessCodeInvalid() {
        var result = register(body("ana@mail.com", true, null)).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_CODE_INVALID");
    }

    @Test
    void register_normalizesEmail() {
        String code = newCode("ana@mail.com");

        var result = register(body(" Ana@Mail.com", true, code)).exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.user.email").isEqualTo("ana@mail.com");
    }

    @Test
    void register_existingEmailWithValidCode_returns409() {
        assertThat(register(body("ana@mail.com", true, newCode(null))).exchange())
                .hasStatus(201);

        var result = register(body("ANA@mail.com", true, newCode(null))).exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void register_failureAfterCodeCheck_doesNotConsumeCode() {
        assertThat(register(body("ana@mail.com", true, newCode(null))).exchange())
                .hasStatus(201);
        String code = newCode(null);

        assertThat(register(body("ana@mail.com", true, code)).exchange()).hasStatus(409);
        Integer used = jdbc.queryForObject(
                "SELECT used_count FROM access_codes WHERE max_uses = 1 AND used_count = 0", Integer.class);
        assertThat(used).isZero();

        assertThat(register(body("bia@mail.com", true, code)).exchange()).hasStatus(201);
    }

    @Test
    void register_termsNotAccepted_returns400() {
        var result = register(body("ana@mail.com", false, newCode(null))).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("acceptTerms");
    }

    @Nested
    @TestPropertySource(properties = "app.registration.mode=OPEN")
    class OpenMode {

        @Test
        void register_openMode_needsNoCode() {
            var result = register(body("ana@mail.com", true, null)).exchange();

            assertThat(result).hasStatus(201);
        }
    }
}
