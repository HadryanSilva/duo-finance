package br.dev.duofinance.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.account.model.Account;
import br.dev.duofinance.account.model.AccountMember;
import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.auth.service.TokenService;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.user.model.User;
import br.dev.duofinance.user.model.UserRole;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@Import(SecurityTest.SecurityTestController.class)
class SecurityTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TokenService tokenService;

    @Autowired
    UserRepository users;

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountMemberRepository members;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private User newUser(String email, UserRole role) {
        User user = new User(email, "Ana", "pt-BR", "2026-11", Instant.now(clock));
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private UUID newAccountWith(User user) {
        Instant now = Instant.now(clock);
        Account account = accounts.saveAndFlush(new Account("Casa", now));
        members.saveAndFlush(new AccountMember(account.getId(), user.getId(), now));
        return account.getId();
    }

    private String bearer(User user, UUID accountId) {
        return "Bearer " + tokenService.issueAccessToken(user, accountId).token();
    }

    @Test
    void noToken_returns401Unauthenticated() {
        assertThat(mvc.get().uri("/api/v1/__test/me"))
                .hasStatus(401)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void noToken_onProtectedRoute_returns401ProblemFromEntryPoint() {
        var result = mvc.get().uri("/api/v1/admin/__test");
        assertThat(result).hasStatus(401).hasContentType("application/problem+json");
        assertThat(result)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void validToken_resolvesCurrentUser() {
        User user = newUser("ana@mail.com", UserRole.USER);
        UUID accountId = newAccountWith(user);

        assertThat(mvc.get().uri("/api/v1/__test/me").header("Authorization", bearer(user, accountId)))
                .hasStatus(200)
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat()
                            .extractingPath("$.userId")
                            .isEqualTo(user.getId().toString());
                    json.assertThat().extractingPath("$.accountId").isEqualTo(accountId.toString());
                    json.assertThat().extractingPath("$.role").isEqualTo("USER");
                });
    }

    @Test
    void issuedToken_expiresIn900Seconds() {
        User user = newUser("ana@mail.com", UserRole.USER);
        UUID accountId = newAccountWith(user);

        assertThat(tokenService.issueAccessToken(user, accountId).expiresInSeconds())
                .isEqualTo(900);
    }

    @Test
    void staleAccountClaim_isRejected() {
        User user = newUser("ana@mail.com", UserRole.USER);
        UUID accountA = newAccountWith(user);
        String token = bearer(user, accountA);
        UUID accountB =
                accounts.saveAndFlush(new Account("Outra", Instant.now(clock))).getId();
        jdbc.update("UPDATE account_members SET account_id = ? WHERE user_id = ?", accountB, user.getId());

        assertThat(mvc.get().uri("/api/v1/__test/me").header("Authorization", token))
                .hasStatus(401)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("SESSION_STALE"));
    }

    @Test
    void deletedUser_tokenIsRejected() {
        User user = newUser("ana@mail.com", UserRole.USER);
        UUID accountId = newAccountWith(user);
        String token = bearer(user, accountId);
        users.deleteById(user.getId());
        users.flush();

        assertThat(mvc.get().uri("/api/v1/__test/me").header("Authorization", token))
                .hasStatus(401)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("SESSION_STALE"));
    }

    @Test
    void expiredToken_isRejected() {
        User user = newUser("ana@mail.com", UserRole.USER);
        UUID accountId = newAccountWith(user);
        String token = bearer(user, accountId);
        clock.advance(Duration.ofMinutes(16));

        assertThat(mvc.get().uri("/api/v1/__test/me").header("Authorization", token))
                .hasStatus(401)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("UNAUTHENTICATED"));
    }

    @Test
    void adminRoute_forbiddenForUser_allowedForAdmin() {
        User user = newUser("ana@mail.com", UserRole.USER);
        User admin = newUser("root@mail.com", UserRole.ADMIN);
        String userToken = bearer(user, newAccountWith(user));
        String adminToken = bearer(admin, newAccountWith(admin));

        var forbidden = mvc.get().uri("/api/v1/admin/__test").header("Authorization", userToken);
        assertThat(forbidden).hasStatus(403).hasContentType("application/problem+json");
        assertThat(forbidden)
                .bodyJson()
                .satisfies(json -> json.assertThat().extractingPath("$.code").isEqualTo("FORBIDDEN"));
        assertThat(mvc.get().uri("/api/v1/admin/__test").header("Authorization", adminToken))
                .hasStatus(200);
    }

    @RestController
    static class SecurityTestController {

        @GetMapping("/api/v1/__test/me")
        CurrentUser me(CurrentUser currentUser) {
            return currentUser;
        }

        @GetMapping("/api/v1/admin/__test")
        String admin() {
            return "ok";
        }
    }
}
