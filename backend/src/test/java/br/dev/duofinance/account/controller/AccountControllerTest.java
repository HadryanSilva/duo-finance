package br.dev.duofinance.account.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Invitation;
import br.dev.duofinance.account.repository.InvitationRepository;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class AccountControllerTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    InvitationRepository invitations;

    @Autowired
    MutableClock clock;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MvcTestResult get(TestSession s) {
        return mvc.get()
                .uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private MvcTestResult patch(TestSession s, String body) {
        return mvc.patch()
                .uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void getAccount_listsMembersAndCurrency() {
        TestSession s = testUsers.registered("ana@mail.com");

        var result = get(s);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.id")
                .isEqualTo(s.accountId().toString());
        assertThat(result).bodyJson().extractingPath("$.currency").isEqualTo("EUR");
        assertThat(result).bodyJson().extractingPath("$.members.length()").isEqualTo(1);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.members[0].userId")
                .isEqualTo(s.userId().toString());
        assertThat(result).bodyJson().extractingPath("$.members[0].email").isEqualTo("ana@mail.com");
        assertThat(result).bodyJson().extractingPath("$.members[0].joinedAt").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.pendingInvitation").isNull();
    }

    @Test
    void getAccount_showsOnlyUnexpiredPendingInvitation() {
        TestSession s = testUsers.registered("ana@mail.com");
        Instant now = Instant.now(clock);
        Invitation invitation = invitations.saveAndFlush(
                new Invitation(s.accountId(), "bia@mail.com", "hash-1", s.userId(), now.plus(Duration.ofDays(7)), now));

        var pending = get(s);
        assertThat(pending)
                .bodyJson()
                .extractingPath("$.pendingInvitation.id")
                .isEqualTo(invitation.getId().toString());
        assertThat(pending)
                .bodyJson()
                .extractingPath("$.pendingInvitation.email")
                .isEqualTo("bia@mail.com");

        jdbc.update("UPDATE invitations SET expires_at = ?", java.sql.Timestamp.from(now.minusSeconds(1)));
        assertThat(get(s)).bodyJson().extractingPath("$.pendingInvitation").isNull();
    }

    @Test
    void patchAccount_renames() {
        TestSession s = testUsers.registered("ana@mail.com");

        var result = patch(s, "{\"name\":\"  Casa Nova \"}");

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Casa Nova");
        assertThat(get(s)).bodyJson().extractingPath("$.name").isEqualTo("Casa Nova");
    }

    @Test
    void patchAccount_rejectsBlankAndTooLongNames() {
        TestSession s = testUsers.registered("ana@mail.com");

        var blank = patch(s, "{\"name\":\"  \"}");
        assertThat(blank).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(blank).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(patch(s, "{\"name\":\"" + "x".repeat(61) + "\"}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getAccount_withoutToken_returns401() {
        assertThat(mvc.get().uri("/api/v1/account").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
