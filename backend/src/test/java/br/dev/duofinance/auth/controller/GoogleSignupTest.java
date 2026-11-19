package br.dev.duofinance.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.access.service.AccessCodeService;
import br.dev.duofinance.auth.google.SignupTicketService;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestGoogleTokens;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class GoogleSignupTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    TestGoogleTokens tokens;

    @Autowired
    AccessCodeService accessCodes;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private String ticketFor(String sub, String email) throws Exception {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"%s\"}".formatted(tokens.idToken(sub, email, true)))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return json.readTree(result.getResponse().getContentAsString())
                .get("signupTicket")
                .asString();
    }

    private MvcTestResult complete(String ticket, String accessCode, String invitationToken) {
        String code = accessCode == null ? "null" : "\"" + accessCode + "\"";
        String invite = invitationToken == null ? "null" : "\"" + invitationToken + "\"";
        return mvc.post()
                .uri("/api/v1/auth/google/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"signupTicket":"%s","displayName":"Novo","locale":"pt-BR","acceptTerms":true,
                         "termsVersion":"2026-11","accessCode":%s,"invitationToken":%s}
                        """.formatted(ticket, code, invite))
                .exchange();
    }

    private String newCode() {
        return accessCodes.generate(null, 1, null, "test", null).plainCode();
    }

    private MvcTestResult me(String accessToken) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private String accessTokenOf(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asString();
    }

    private void invite(TestSession inviter, String email) {
        assertThat(mvc.post()
                        .uri("/api/v1/account/invitations")
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(inviter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}")
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void complete_withAccessCode_createsVerifiedUserWithoutVerificationEmail() throws Exception {
        String ticket = ticketFor("g-1", "novo@mail.com");

        var result = complete(ticket, newCode(), null);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("df_refresh=");
        assertThat(result).bodyJson().extractingPath("$.user.email").isEqualTo("novo@mail.com");
        assertThat(me(accessTokenOf(result)))
                .bodyJson()
                .extractingPath("$.emailVerified")
                .isEqualTo(true);
        assertThat(emails.lastTo("novo@mail.com")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM user_identities i JOIN users u ON u.id = i.user_id"
                                + " WHERE u.email = 'novo@mail.com' AND i.provider = 'LOCAL'",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM user_identities WHERE provider = 'GOOGLE' AND provider_subject = 'g-1'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void complete_withoutCredential_inviteOnly_is400AccessCodeInvalid() throws Exception {
        var result = complete(ticketFor("g-1", "novo@mail.com"), null, null);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_CODE_INVALID");
    }

    @Test
    void complete_withInvitation_joinsInviterAccount() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        invite(ana, "novo@mail.com");
        String token = testUsers.invitationToken("novo@mail.com");

        var result = complete(ticketFor("g-1", "novo@mail.com"), null, token);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        var me = me(accessTokenOf(result));
        assertThat(me)
                .bodyJson()
                .extractingPath("$.accountId")
                .isEqualTo(ana.accountId().toString());
        assertThat(me).bodyJson().extractingPath("$.onboarded").isEqualTo(true);
    }

    @Test
    void complete_invitationForOtherEmail_isInviteInvalid() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        invite(ana, "rui@mail.com");
        String token = testUsers.invitationToken("rui@mail.com");

        var result = complete(ticketFor("g-1", "novo@mail.com"), null, token);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVITE_INVALID");
    }

    @Test
    void complete_expiredTicket_is401() throws Exception {
        String ticket = ticketFor("g-1", "novo@mail.com");
        clock.advance(SignupTicketService.TTL.plus(Duration.ofMinutes(1)));

        var result = complete(ticket, newCode(), null);

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("GOOGLE_SIGNUP_TICKET_INVALID");
    }

    @Test
    void complete_twice_secondIs409() throws Exception {
        String ticket = ticketFor("g-1", "novo@mail.com");
        assertThat(complete(ticket, newCode(), null)).hasStatus(HttpStatus.CREATED);

        var result = complete(ticket, newCode(), null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void complete_thenGoogleLogin_authenticates() throws Exception {
        assertThat(complete(ticketFor("g-1", "novo@mail.com"), newCode(), null)).hasStatus(HttpStatus.CREATED);

        var result = mvc.post()
                .uri("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"credential\":\"%s\"}".formatted(tokens.idToken("g-1", "novo@mail.com", true)))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("AUTHENTICATED");
    }

    @Nested
    @TestPropertySource(properties = "app.registration.mode=OPEN")
    class OpenMode {

        @Test
        void complete_openMode_needsNoCode() throws Exception {
            var result = complete(ticketFor("g-1", "novo@mail.com"), null, null);

            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(me(accessTokenOf(result))).hasStatus(HttpStatus.OK);
        }
    }
}
