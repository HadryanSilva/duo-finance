package br.dev.duofinance.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.shared.email.EmailMessage;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.subscription.repository.SubscriptionRepository;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import br.dev.duofinance.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class DeleteMeTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    MutableClock clock;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    CategoryRepository categories;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    UserRepository users;

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private MvcTestResult deleteMe(TestSession s, String password) {
        return mvc.delete()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + password + "\"}")
                .exchange();
    }

    /** Ana invites Rui and Rui accepts. */
    private TestSession[] couple() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.registered("rui@mail.com");
        assertThat(mvc.post()
                        .uri("/api/v1/account/invitations")
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"rui@mail.com\"}")
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
        Awaitility.await().untilAsserted(() -> {
            EmailMessage m = emails.lastTo("rui@mail.com");
            assertThat(m).isNotNull();
            assertThat(emails.extractLink(m, "/invite/")).isNotNull();
        });
        String link = emails.extractLink(emails.lastTo("rui@mail.com"), "/invite/");
        String token = link.substring(link.indexOf("/invite/") + "/invite/".length());
        var accepted = mvc.post()
                .uri("/api/v1/account/invitations/" + token + "/accept")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(rui))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmDiscard\":false}")
                .exchange();
        assertThat(accepted).hasStatus(HttpStatus.OK);
        String fresh =
                accepted.getResponse().getContentAsString().replaceFirst(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        return new TestSession[] {ana, new TestSession(rui.userId(), ana.accountId(), fresh, rui.refreshCookie())};
    }

    @Test
    void deleteMe_wrongPassword_returns401() {
        TestSession ana = testUsers.verified("ana@mail.com");

        var result = deleteMe(ana, "wrong-password");

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        assertThat(users.existsById(ana.userId())).isTrue();
    }

    @Test
    void deleteMe_blankPassword_returns400() {
        TestSession ana = testUsers.verified("ana@mail.com");

        assertThat(deleteMe(ana, "")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteMe_soleMember_deletesAccountEntirely() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        assertThat(categories.findAll()).isNotEmpty();
        assertThat(subscriptions.findAll()).hasSize(1);

        var result = deleteMe(ana, TestUsers.PASSWORD);

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("df_refresh=;")
                .contains("Max-Age=0");
        assertThat(users.existsById(ana.userId())).isFalse();
        assertThat(accounts.existsById(ana.accountId())).isFalse();
        assertThat(subscriptions.findAll()).isEmpty();
        assertThat(categories.findAll()).isEmpty();
    }

    @Test
    void deleteMe_inCouple_keepsAccountForPartner() throws Exception {
        TestSession[] pair = couple();
        TestSession ana = pair[0];
        TestSession rui = pair[1];

        assertThat(deleteMe(ana, TestUsers.PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(users.existsById(ana.userId())).isFalse();
        assertThat(accounts.existsById(ana.accountId())).isTrue();
        assertThat(subscriptions.findAll()).hasSize(1);
        var account = mvc.get()
                .uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(rui))
                .exchange();
        assertThat(account).hasStatus(HttpStatus.OK);
        assertThat(account).bodyJson().extractingPath("$.members.length()").isEqualTo(1);
        assertThat(invitations.findAll()).singleElement().satisfies(i -> {
            assertThat(i.getInvitedBy()).isNull();
            assertThat(i.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        });
    }

    @Test
    void deleteMe_inCouple_cancelsPendingInvitationsTheUserCreated() throws Exception {
        TestSession[] pair = couple();
        TestSession ana = pair[0];
        invitations.saveAndFlush(new Invitation(
                ana.accountId(),
                "bia@mail.com",
                SecureTokens.sha256Hex("pending-token"),
                ana.userId(),
                Instant.now(clock).plus(Duration.ofDays(7)),
                Instant.now(clock)));

        assertThat(deleteMe(ana, TestUsers.PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(invitations.findAll())
                .filteredOn(i -> "bia@mail.com".equals(i.getEmail()))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.getStatus()).isEqualTo(InvitationStatus.CANCELED);
                    assertThat(i.getInvitedBy()).isNull();
                });
    }

    @Test
    void deleteMe_revokesSessions() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");

        assertThat(deleteMe(ana, TestUsers.PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.post()
                        .uri("/api/v1/auth/refresh")
                        .cookie(new Cookie("df_refresh", ana.refreshCookie()))
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
