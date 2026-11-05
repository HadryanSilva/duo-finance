package br.dev.duofinance.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AuthSessionServiceTest {

    @Autowired
    AuthSessionService sessions;

    @Autowired
    UserRepository users;

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountMemberRepository members;

    @Autowired
    MutableClock clock;

    User user;
    UUID accountId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now(clock);
        user = users.saveAndFlush(new User("ana@mail.com", "Ana", "pt-BR", "2026-11", now));
        accountId = accounts.saveAndFlush(new Account("Casa", now)).getId();
        members.saveAndFlush(new AccountMember(accountId, user.getId(), now));
    }

    private void assertRefreshFails(String raw, ErrorCode code) {
        assertThatThrownBy(() -> sessions.refresh(raw))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void refresh_rotatesToken() {
        String raw1 = sessions.start(user).rawRefreshToken();

        String raw2 = sessions.refresh(raw1).rawRefreshToken();

        assertThat(raw2).isNotEqualTo(raw1);
        assertThat(sessions.refresh(raw2).rawRefreshToken()).isNotEqualTo(raw2);
    }

    @Test
    void refresh_reuseAfterGraceWindow_revokesFamily() {
        String raw1 = sessions.start(user).rawRefreshToken();
        String raw2 = sessions.refresh(raw1).rawRefreshToken();
        clock.advance(Duration.ofSeconds(11));

        assertRefreshFails(raw1, ErrorCode.REFRESH_TOKEN_INVALID);
        assertRefreshFails(raw2, ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void refresh_reuseWithinGraceWindow_doesNotRevokeFamily() {
        String raw1 = sessions.start(user).rawRefreshToken();
        String raw2 = sessions.refresh(raw1).rawRefreshToken();
        clock.advance(Duration.ofSeconds(3));

        assertRefreshFails(raw1, ErrorCode.REFRESH_RACE);
        assertThat(sessions.refresh(raw2).rawRefreshToken()).isNotNull();
    }

    @Test
    void refresh_expiredAfter30Days() {
        String raw1 = sessions.start(user).rawRefreshToken();
        clock.advance(Duration.ofDays(30).plusSeconds(1));

        assertRefreshFails(raw1, ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void refresh_unknownToken_isInvalid() {
        assertRefreshFails("nope", ErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void refresh_usesCurrentMembershipAccountId() {
        String raw1 = sessions.start(user).rawRefreshToken();
        Instant now = Instant.now(clock);
        UUID other = accounts.saveAndFlush(new Account("Nova", now)).getId();
        members.deleteAll();
        members.flush();
        members.saveAndFlush(new AccountMember(other, user.getId(), now));

        SessionTokens refreshed = sessions.refresh(raw1);

        assertThat(refreshed.accountId()).isEqualTo(other).isNotEqualTo(accountId);
    }

    @Test
    void revokeAll_invalidatesEveryFamily() {
        String raw1 = sessions.start(user).rawRefreshToken();
        String raw2 = sessions.start(user).rawRefreshToken();

        sessions.revokeAll(user.getId());

        assertRefreshFails(raw1, ErrorCode.REFRESH_TOKEN_INVALID);
        assertRefreshFails(raw2, ErrorCode.REFRESH_TOKEN_INVALID);
    }
}
