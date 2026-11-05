package br.dev.duofinance.support;

import br.dev.duofinance.access.service.AccessCodeService;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.user.model.UserRole;
import br.dev.duofinance.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registers real users through the public endpoint so tests start from a realistic session. */
@TestComponent
public class TestUsers {

    public static final String PASSWORD = "s3cret-pass";

    private final MockMvcTester mvc;
    private final AccessCodeService accessCodes;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuthSessionService sessions;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public TestUsers(
            MockMvcTester mvc,
            AccessCodeService accessCodes,
            JdbcTemplate jdbc,
            UserRepository users,
            AuthSessionService sessions,
            Clock clock) {
        this.mvc = mvc;
        this.accessCodes = accessCodes;
        this.jdbc = jdbc;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public record TestSession(UUID userId, UUID accountId, String accessToken, String refreshCookie) {}

    public TestSession registered(String email) {
        String code = accessCodes.generate(null, 1, null, "test", null).plainCode();
        String body = """
                {"email":"%s","password":"%s","displayName":"Test User","locale":"pt-BR",
                 "acceptTerms":true,"termsVersion":"2026-11","accessCode":"%s"}
                """.formatted(email, PASSWORD, code);
        var result = mvc.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException(
                    "Registration failed: " + result.getResponse().getStatus());
        }
        try {
            JsonNode root = json.readTree(result.getResponse().getContentAsString());
            String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
            String refresh = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
            return new TestSession(
                    UUID.fromString(root.path("user").path("id").asString()),
                    UUID.fromString(root.path("user").path("accountId").asString()),
                    root.path("accessToken").asString(),
                    refresh);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public TestSession verified(String email) {
        TestSession session = registered(email);
        jdbc.update(
                "UPDATE users SET email_verified_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now(clock)),
                session.userId());
        return session;
    }

    public TestSession admin(String email) {
        TestSession session = verified(email);
        var user = users.findById(session.userId()).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        var fresh = sessions.start(user);
        return new TestSession(
                session.userId(), session.accountId(), fresh.access().token(), fresh.rawRefreshToken());
    }

    public static String bearer(TestSession session) {
        return "Bearer " + session.accessToken();
    }
}
