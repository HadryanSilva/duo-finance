package br.dev.duofinance.access.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.access.repository.WaitlistEntryRepository;
import br.dev.duofinance.shared.email.EmailMessage;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AdminAccessTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    WaitlistEntryRepository entries;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private void join(String email) {
        mvc.post()
                .uri("/api/v1/public/waitlist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"locale\":\"en\"}")
                .exchange();
    }

    private MvcTestResult adminPost(TestSession admin, String uri, String body) {
        var req = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(admin));
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return req.exchange();
    }

    private MvcTestResult adminGet(TestSession admin, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(admin))
                .exchange();
    }

    private UUID entryId(String email) {
        return entries.findAll().stream()
                .filter(e -> e.getEmail().equals(email))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    /** Waits for the access-code e-mail to the address and returns the code from its /register link. */
    private String awaitCode(String email, int nth) {
        Awaitility.await().untilAsserted(() -> assertThat(codeMails(email)).hasSize(nth));
        EmailMessage mail = codeMails(email).get(nth - 1);
        String link = emails.extractLink(mail, "/register");
        return URI.create(link).getQuery().substring("code=".length());
    }

    private List<EmailMessage> codeMails(String email) {
        return emails.sent().stream()
                .filter(m -> m.to().equalsIgnoreCase(email) && m.html().contains("/register?code="))
                .toList();
    }

    private MvcTestResult register(String email, String code) {
        return mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"s3cret-pass","displayName":"Ana","locale":"en",
                         "acceptTerms":true,"termsVersion":"2026-11","accessCode":"%s"}
                        """.formatted(email, code))
                .exchange();
    }

    @Test
    void admin_approve_emailsCodeThatRegisters() {
        TestSession admin = testUsers.admin("admin@mail.com");
        join("Ana@x.com");

        var approved = adminPost(admin, "/api/v1/admin/waitlist/" + entryId("ana@x.com") + "/approve", null);

        assertThat(approved).hasStatus(HttpStatus.OK);
        assertThat(approved).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
        String code = awaitCode("ana@x.com", 1);
        assertThat(register("ana@x.com", code)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void admin_approveTwice_revokesPreviousCode() {
        TestSession admin = testUsers.admin("admin@mail.com");
        join("ana@x.com");
        String uri = "/api/v1/admin/waitlist/" + entryId("ana@x.com") + "/approve";

        adminPost(admin, uri, null);
        String oldCode = awaitCode("ana@x.com", 1);
        adminPost(admin, uri, null);
        String newCode = awaitCode("ana@x.com", 2);

        assertThat(newCode).isNotEqualTo(oldCode);
        var stale = register("ana@x.com", oldCode);
        assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_CODE_INVALID");
        assertThat(register("ana@x.com", newCode)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void admin_rejectedEntry_canStillBeApproved() {
        TestSession admin = testUsers.admin("admin@mail.com");
        join("ana@x.com");
        String id = entryId("ana@x.com").toString();

        var rejected = adminPost(admin, "/api/v1/admin/waitlist/" + id + "/reject", null);
        var approved = adminPost(admin, "/api/v1/admin/waitlist/" + id + "/approve", null);

        assertThat(rejected).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(approved).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
    }

    @Test
    void admin_unknownEntry_is404() {
        TestSession admin = testUsers.admin("admin@mail.com");

        var result = adminPost(admin, "/api/v1/admin/waitlist/" + UUID.randomUUID() + "/approve", null);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void admin_listWaitlist_filtersByStatusInStableShape() {
        TestSession admin = testUsers.admin("admin@mail.com");
        join("a@x.com");
        join("b@x.com");
        adminPost(admin, "/api/v1/admin/waitlist/" + entryId("b@x.com") + "/reject", null);

        var pending = adminGet(admin, "/api/v1/admin/waitlist?status=PENDING&page=0&size=20");
        var all = adminGet(admin, "/api/v1/admin/waitlist?size=1");

        assertThat(pending).hasStatus(HttpStatus.OK);
        assertThat(pending).bodyJson().extractingPath("$.content.length()").isEqualTo(1);
        assertThat(pending).bodyJson().extractingPath("$.content[0].email").isEqualTo("a@x.com");
        assertThat(pending).bodyJson().extractingPath("$.totalElements").isEqualTo(1);
        assertThat(all).bodyJson().extractingPath("$.size").isEqualTo(1);
        assertThat(all).bodyJson().extractingPath("$.totalElements").isEqualTo(2);
        assertThat(all).bodyJson().extractingPath("$.totalPages").isEqualTo(2);
        assertThat(all).bodyJson().extractingPath("$.content[0].email").isEqualTo("a@x.com");
    }

    @Test
    void admin_createCode_returnsPlainCodeOnce() throws Exception {
        TestSession admin = testUsers.admin("admin@mail.com");

        var created = adminPost(admin, "/api/v1/admin/access-codes", "{\"email\":\" Bob@X.com \",\"note\":\"vip\"}");

        assertThat(created).hasStatus(HttpStatus.CREATED);
        var body = json.readTree(created.getResponse().getContentAsString());
        assertThat(body.path("plainCode").asString()).isNotBlank();
        assertThat(body.path("code").path("email").asString()).isEqualTo("bob@x.com");
        assertThat(body.path("code").path("maxUses").asInt()).isEqualTo(1);

        var list = adminGet(admin, "/api/v1/admin/access-codes");
        assertThat(list).hasStatus(HttpStatus.OK);
        assertThat(list.getResponse().getContentAsString()).doesNotContain("plainCode");
        assertThat(list.getResponse().getContentAsString())
                .doesNotContain(body.path("plainCode").asString());
        assertThat(list).bodyJson().extractingPath("$[0].note").isEqualTo("vip");
    }

    @Test
    void admin_createCode_validatesMaxUses() {
        TestSession admin = testUsers.admin("admin@mail.com");

        assertThat(adminPost(admin, "/api/v1/admin/access-codes", "{\"maxUses\":0}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(adminPost(admin, "/api/v1/admin/access-codes", "{\"maxUses\":1001}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void admin_revokedCode_cannotRegister() throws Exception {
        TestSession admin = testUsers.admin("admin@mail.com");
        var created = adminPost(admin, "/api/v1/admin/access-codes", "{}");
        var body = json.readTree(created.getResponse().getContentAsString());
        String id = body.path("code").path("id").asString();

        var revoked = adminPost(admin, "/api/v1/admin/access-codes/" + id + "/revoke", null);
        var attempt = register("carl@x.com", body.path("plainCode").asString());

        assertThat(revoked).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(attempt).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_CODE_INVALID");
    }

    @Test
    void adminRoutes_forbiddenForRegularUser() {
        TestSession user = testUsers.registered("user@mail.com");

        var list = adminGet(user, "/api/v1/admin/waitlist");
        var create = adminPost(user, "/api/v1/admin/access-codes", "{}");

        assertThat(list).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(list).bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN");
        assertThat(create).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(create).bodyJson().extractingPath("$.code").isEqualTo("FORBIDDEN");
    }
}
