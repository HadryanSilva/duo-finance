package br.dev.duofinance.account.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.shared.email.EmailMessage;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
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
class InvitationTest {

    private static final String BASE = "/api/v1/account/invitations";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    MutableClock clock;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CategoryRepository categories;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthSessionService sessions;

    @Autowired
    UserRepository users;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    // --- helpers ---

    private MvcTestResult invite(TestSession inviter, String email) {
        return mvc.post()
                .uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(inviter))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")
                .exchange();
    }

    /** Invites and returns the raw token taken from the delivered e-mail. */
    private String inviteAndGetToken(TestSession inviter, String email) {
        assertThat(invite(inviter, email)).hasStatus(HttpStatus.CREATED);
        String normalized = SecureTokens.normalizeEmail(email);
        Awaitility.await().untilAsserted(() -> {
            EmailMessage message = emails.lastTo(normalized);
            assertThat(message).isNotNull();
            assertThat(emails.extractLink(message, "/invite/")).isNotNull();
        });
        String link = emails.extractLink(emails.lastTo(normalized), "/invite/");
        return link.substring(link.indexOf("/invite/") + "/invite/".length());
    }

    private MvcTestResult preview(String token) {
        return mvc.get().uri(BASE + "/" + token).exchange();
    }

    private MvcTestResult accept(TestSession session, String token, boolean confirmDiscard) {
        return mvc.post()
                .uri(BASE + "/" + token + "/accept")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmDiscard\":" + confirmDiscard + "}")
                .exchange();
    }

    private MvcTestResult getAccount(String accessToken) {
        return mvc.get()
                .uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    private String accessTokenOf(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .path("accessToken")
                .asString();
    }

    private static void assertError(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    /** Ana invites Bia and Bia accepts, leaving a full couple. */
    private TestSession[] couple(String first, String second) {
        TestSession a = testUsers.verified(first);
        TestSession b = testUsers.registered(second);
        String token = inviteAndGetToken(a, second);
        assertThat(accept(b, token, false)).hasStatus(HttpStatus.OK);
        return new TestSession[] {a, b};
    }

    // --- create ---

    @Test
    void invite_requiresVerifiedEmail() {
        TestSession ana = testUsers.registered("ana@mail.com");

        assertError(invite(ana, "rui@mail.com"), HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED");
    }

    @Test
    void invite_sendsLocalizedEmailWithLink() {
        TestSession ana = testUsers.verified("ana@mail.com");

        var result = invite(ana, " Rui@Mail.com ");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("rui@mail.com");
        assertThat(result).bodyJson().extractingPath("$.id").isNotNull();
        Awaitility.await()
                .untilAsserted(() -> assertThat(emails.lastTo("rui@mail.com")).isNotNull());
        EmailMessage message = emails.lastTo("rui@mail.com");
        assertThat(message.subject()).contains("convidou você");
        assertThat(message.html()).contains("/invite/");
        assertThat(emails.extractLink(message, "/invite/")).startsWith("http://localhost:5173/invite/");

        Invitation stored = invitations.findAll().getFirst();
        assertThat(stored.getExpiresAt())
                .isEqualTo(Instant.now(clock).plus(Duration.ofDays(7)).truncatedTo(ChronoUnit.MICROS));
        String token = emails.extractLink(message, "/invite/").replaceFirst(".*/invite/", "");
        assertThat(stored.getTokenHash()).isEqualTo(SecureTokens.sha256Hex(token));
    }

    @Test
    void invite_rejectsSecondPendingAndSelf() {
        TestSession ana = testUsers.verified("ana@mail.com");

        assertError(invite(ana, " ANA@mail.com"), HttpStatus.CONFLICT, "INVITE_SELF");
        assertThat(invite(ana, "rui@mail.com")).hasStatus(HttpStatus.CREATED);
        assertError(invite(ana, "bia@mail.com"), HttpStatus.CONFLICT, "INVITE_PENDING_EXISTS");
    }

    @Test
    void invite_afterPreviousExpired_marksItExpiredAndCreatesNew() {
        TestSession ana = testUsers.verified("ana@mail.com");
        assertThat(invite(ana, "rui@mail.com")).hasStatus(HttpStatus.CREATED);
        clock.advance(Duration.ofDays(7));

        assertThat(invite(renewed(ana), "bia@mail.com")).hasStatus(HttpStatus.CREATED);

        assertThat(invitations.findAll())
                .extracting(Invitation::getEmail, Invitation::getStatus)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("rui@mail.com", InvitationStatus.EXPIRED),
                        Tuple.tuple("bia@mail.com", InvitationStatus.PENDING));
    }

    @Test
    void invite_whenCoupleIsFull_returns409AccountFull() {
        TestSession[] pair = couple("ana@mail.com", "rui@mail.com");

        assertError(invite(pair[0], "bia@mail.com"), HttpStatus.CONFLICT, "ACCOUNT_FULL");
    }

    // --- preview ---

    @Test
    void preview_isPublicAndShowsInviter() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");

        var result = preview(token);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.inviterName").isEqualTo("Test User");
        assertThat(result).bodyJson().extractingPath("$.accountName").isEqualTo("Finanças de Test User");
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("rui@mail.com");
        assertThat(result).bodyJson().extractingPath("$.existingUser").isEqualTo(false);

        testUsers.registered("rui@mail.com");
        assertThat(preview(token)).bodyJson().extractingPath("$.existingUser").isEqualTo(true);
    }

    @Test
    void preview_unknownOrCanceled_returns400InviteInvalid() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");
        UUID id = invitations.findAll().getFirst().getId();

        assertError(preview("nope"), HttpStatus.BAD_REQUEST, "INVITE_INVALID");
        assertThat(mvc.delete()
                        .uri(BASE + "/" + id)
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(ana))
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        assertError(preview(token), HttpStatus.BAD_REQUEST, "INVITE_INVALID");
        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.CANCELED);
    }

    @Test
    void preview_expiredAfter7Days_returns410() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");
        clock.advance(Duration.ofDays(7));

        assertError(preview(token), HttpStatus.GONE, "INVITE_EXPIRED");
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.EXPIRED);
        assertError(preview(token), HttpStatus.GONE, "INVITE_EXPIRED");
    }

    // --- cancel ---

    @Test
    void cancel_otherAccountsInvitation_returns404() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bia = testUsers.verified("bia@mail.com");
        assertThat(invite(ana, "rui@mail.com")).hasStatus(HttpStatus.CREATED);
        UUID id = invitations.findAll().getFirst().getId();

        var result = mvc.delete()
                .uri(BASE + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(bia))
                .exchange();

        assertError(result, HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(invitations.findById(id).orElseThrow().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void cancel_twice_returns404AndAllowsNewInvite() {
        TestSession ana = testUsers.verified("ana@mail.com");
        assertThat(invite(ana, "rui@mail.com")).hasStatus(HttpStatus.CREATED);
        UUID id = invitations.findAll().getFirst().getId();
        var delete = mvc.delete().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(ana));

        assertThat(delete.exchange()).hasStatus(HttpStatus.NO_CONTENT);
        assertError(delete.exchange(), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(invite(ana, "bia@mail.com")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void cancel_withoutToken_returns401() {
        assertThat(mvc.delete().uri(BASE + "/" + UUID.randomUUID()).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // --- accept ---

    @Test
    void accept_movesUserAndDeletesEmptySoloAccount() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");

        var result = accept(rui, token, false);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.user.accountId")
                .isEqualTo(ana.accountId().toString());
        assertThat(result).bodyJson().extractingPath("$.user.emailVerified").isEqualTo(true);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("df_refresh=");
        var account = getAccount(accessTokenOf(result));
        assertThat(account).hasStatus(HttpStatus.OK);
        assertThat(account).bodyJson().extractingPath("$.members.length()").isEqualTo(2);
        assertThat(account).bodyJson().extractingPath("$.pendingInvitation").isNull();
        assertThat(accounts.existsById(rui.accountId())).isFalse();
        Invitation accepted = invitations.findAll().getFirst();
        assertThat(accepted.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(accepted.getAcceptedAt()).isNotNull();
    }

    @Test
    void accept_matchesInviteEmailIgnoringCase() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        String token = inviteAndGetToken(ana, "Rui@Mail.com");

        assertThat(accept(rui, token, false)).hasStatus(HttpStatus.OK);
    }

    @Test
    void accept_soloAccountWithCustomData_requiresConfirmDiscard() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        categories.saveAndFlush(new Category(
                rui.accountId(), "Hobby", CategoryKind.EXPENSE, "#112233", "star", false, Instant.now(clock)));
        String token = inviteAndGetToken(ana, "rui@mail.com");

        assertError(accept(rui, token, false), HttpStatus.CONFLICT, "ACCOUNT_HAS_DATA");
        assertThat(accounts.existsById(rui.accountId())).isTrue();

        assertThat(accept(rui, token, true)).hasStatus(HttpStatus.OK);
        assertThat(accounts.existsById(rui.accountId())).isFalse();
        assertThat(categories.countByAccountId(rui.accountId())).isZero();
    }

    @Test
    void accept_whileInFullCouple_returns409AlreadyInCouple() {
        TestSession[] pair = couple("ana@mail.com", "bia@mail.com");
        TestSession carl = testUsers.verified("carl@mail.com");
        String token = inviteAndGetToken(carl, "bia@mail.com");
        TestSession bia = pair[1];

        assertError(accept(renewed(bia), token, true), HttpStatus.CONFLICT, "ALREADY_IN_COUPLE");
    }

    @Test
    void accept_ownInvitation_returns409InviteSelf() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String raw = SecureTokens.generate();
        Instant now = Instant.now(clock);
        invitations.saveAndFlush(new Invitation(
                ana.accountId(),
                "ana@mail.com",
                SecureTokens.sha256Hex(raw),
                ana.userId(),
                now.plus(Duration.ofDays(7)),
                now));

        assertError(accept(ana, raw, true), HttpStatus.CONFLICT, "INVITE_SELF");
    }

    @Test
    void accept_byOtherEmail_returns404() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bia = testUsers.registered("bia@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");

        assertError(accept(bia, token, true), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void accept_expiredOrInvalid_isRejected() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");

        assertError(accept(rui, "nope", false), HttpStatus.BAD_REQUEST, "INVITE_INVALID");
        clock.advance(Duration.ofDays(7));
        assertError(accept(renewed(rui), token, false), HttpStatus.GONE, "INVITE_EXPIRED");
    }

    @Test
    void accept_withoutToken_returns401() {
        assertThat(mvc.post().uri(BASE + "/abc/accept").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accept_oldAccessTokenBecomesStale() {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");
        assertThat(accept(rui, token, false)).hasStatus(HttpStatus.OK);

        assertError(getAccount(rui.accessToken()), HttpStatus.UNAUTHORIZED, "SESSION_STALE");
    }

    // --- sign-up by invitation ---

    @Test
    void register_withInvitationToken_joinsCoupleWithoutCodeAndIsVerified() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");

        var result = registerWithInvitation(" Rui@Mail.com", token);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.user.emailVerified").isEqualTo(true);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.user.accountId")
                .isEqualTo(ana.accountId().toString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM user_tokens t JOIN users u ON u.id = t.user_id"
                                + " WHERE u.email = 'rui@mail.com' AND t.type = 'EMAIL_VERIFICATION'",
                        Long.class))
                .isZero();
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void register_withInvitationTokenForOtherEmail_returns400InviteInvalid() {
        TestSession ana = testUsers.verified("ana@mail.com");
        String token = inviteAndGetToken(ana, "rui@mail.com");

        assertError(registerWithInvitation("bia@mail.com", token), HttpStatus.BAD_REQUEST, "INVITE_INVALID");
        assertThat(invitations.findAll().getFirst().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    private MvcTestResult registerWithInvitation(String email, String token) {
        String body = """
                {"email":"%s","password":"s3cret-pass","displayName":"Rui","locale":"en",
                 "acceptTerms":true,"termsVersion":"2026-11","invitationToken":"%s"}
                """.formatted(email, token);
        return mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    /** A new session for the user, reflecting their current account and the current (possibly advanced) clock. */
    private TestSession renewed(TestSession session) {
        var fresh = sessions.start(users.findById(session.userId()).orElseThrow());
        return new TestSession(
                session.userId(), fresh.accountId(), fresh.access().token(), fresh.rawRefreshToken());
    }
}
