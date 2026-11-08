package br.dev.duofinance.account.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.model.InvitationStatus;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.category.repository.CategoryRepository;
import br.dev.duofinance.shared.token.SecureTokens;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.RecordingEmailSender;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Duration;
import java.time.Instant;
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
class LeaveTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    RecordingEmailSender emails;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    MutableClock clock;

    @Autowired
    CategoryRepository categories;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    private MvcTestResult leave(TestSession s) {
        return mvc.post()
                .uri("/api/v1/account/leave")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private MvcTestResult getAccount(String accessToken) {
        return mvc.get()
                .uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange();
    }

    @Test
    void leave_soloAccount_returns409() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");

        var result = leave(ana);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_IN_COUPLE");
    }

    @Test
    void leave_givesLeaverFreshAccountAndKeepsDataWithPartner() throws Exception {
        TestSession[] pair = testUsers.couple("ana@mail.com", "rui@mail.com");
        TestSession ana = pair[0];
        TestSession rui = pair[1];
        assertThat(mvc.patch()
                        .uri("/api/v1/account")
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(ana))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana & Rui\"}")
                        .exchange())
                .hasStatus(HttpStatus.OK);

        var result = leave(rui);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith("df_refresh=");
        String newAccountId = json.readTree(result.getResponse().getContentAsString())
                .path("user")
                .path("accountId")
                .asString();
        assertThat(newAccountId).isNotEqualTo(ana.accountId().toString());
        String newToken = json.readTree(result.getResponse().getContentAsString())
                .path("accessToken")
                .asString();
        var ruiAccount = getAccount(newToken);
        assertThat(ruiAccount).hasStatus(HttpStatus.OK);
        assertThat(ruiAccount).bodyJson().extractingPath("$.members.length()").isEqualTo(1);
        assertThat(ruiAccount).bodyJson().extractingPath("$.name").isEqualTo("Finanças de Test User");
        assertThat(categories.findAll().stream()
                        .filter(c -> c.getAccountId().toString().equals(newAccountId))
                        .count())
                .isPositive();
        var anaAccount = getAccount(ana.accessToken());
        assertThat(anaAccount).hasStatus(HttpStatus.OK);
        assertThat(anaAccount).bodyJson().extractingPath("$.members.length()").isEqualTo(1);
        assertThat(anaAccount).bodyJson().extractingPath("$.name").isEqualTo("Ana & Rui");
    }

    @Test
    void leave_cancelsPendingInvitationOfOldAccount() throws Exception {
        TestSession[] pair = testUsers.couple("ana@mail.com", "rui@mail.com");
        invitations.saveAndFlush(new Invitation(
                pair[0].accountId(),
                "bia@mail.com",
                SecureTokens.sha256Hex("pending-token"),
                pair[0].userId(),
                Instant.now(clock).plus(Duration.ofDays(7)),
                Instant.now(clock)));

        assertThat(leave(pair[1])).hasStatus(HttpStatus.OK);

        assertThat(invitations.findAll())
                .filteredOn(i -> "bia@mail.com".equals(i.getEmail()))
                .extracting(Invitation::getStatus)
                .containsExactly(InvitationStatus.CANCELED);
    }

    @Test
    void leave_oldTokenIsStale() throws Exception {
        TestSession rui = testUsers.couple("ana@mail.com", "rui@mail.com")[1];

        assertThat(leave(rui)).hasStatus(HttpStatus.OK);

        var stale = getAccount(rui.accessToken());
        assertThat(stale).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(stale).bodyJson().extractingPath("$.code").isEqualTo("SESSION_STALE");
    }
}
