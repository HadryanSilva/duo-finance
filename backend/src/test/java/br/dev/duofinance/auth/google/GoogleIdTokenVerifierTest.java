package br.dev.duofinance.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "cid";

    private static Jwt jwt(Map<String, Object> overrides) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", List.of(CLIENT_ID));
        claims.put("sub", "g-1");
        claims.put("email", "Ana@Mail.com");
        claims.put("email_verified", true);
        claims.put("name", "Ana Google");
        claims.putAll(overrides);
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static GoogleIdTokenVerifier verifierReturning(Jwt jwt, String clientId) {
        return new GoogleIdTokenVerifier(token -> jwt, clientId);
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    void verify_validToken_returnsNormalizedIdentity() {
        var identity = verifierReturning(jwt(Map.of()), CLIENT_ID).verify("t");

        assertThat(identity.subject()).isEqualTo("g-1");
        assertThat(identity.email()).isEqualTo("ana@mail.com");
        assertThat(identity.name()).isEqualTo("Ana Google");
    }

    @Test
    void verify_issWithoutScheme_accepted() {
        var identity = verifierReturning(jwt(Map.of("iss", "accounts.google.com")), CLIENT_ID)
                .verify("t");

        assertThat(identity.subject()).isEqualTo("g-1");
    }

    @Test
    void verify_wrongIssuer_isTokenInvalid() {
        var verifier = verifierReturning(jwt(Map.of("iss", "https://evil.example")), CLIENT_ID);

        assertCode(() -> verifier.verify("t"), ErrorCode.GOOGLE_TOKEN_INVALID);
    }

    @Test
    void verify_wrongAudience_isTokenInvalid() {
        var verifier = verifierReturning(jwt(Map.of("aud", List.of("other"))), CLIENT_ID);

        assertCode(() -> verifier.verify("t"), ErrorCode.GOOGLE_TOKEN_INVALID);
    }

    @Test
    void verify_decoderThrows_isTokenInvalid() {
        var verifier = new GoogleIdTokenVerifier(
                token -> {
                    throw new BadJwtException("bad signature");
                },
                CLIENT_ID);

        assertCode(() -> verifier.verify("t"), ErrorCode.GOOGLE_TOKEN_INVALID);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void verify_jwksUnavailable_isTokenInvalidAndLogsWarnWithoutToken(CapturedOutput output) {
        var verifier = new GoogleIdTokenVerifier(
                token -> {
                    throw new JwtException("Couldn't retrieve remote JWK set");
                },
                CLIENT_ID);

        assertCode(() -> verifier.verify("secret-id-token"), ErrorCode.GOOGLE_TOKEN_INVALID);
        assertThat(output).contains("WARN").contains("JWK set").doesNotContain("secret-id-token");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void verify_badToken_doesNotWarn(CapturedOutput output) {
        var verifier = new GoogleIdTokenVerifier(
                token -> {
                    throw new BadJwtException("bad signature");
                },
                CLIENT_ID);

        assertCode(() -> verifier.verify("t"), ErrorCode.GOOGLE_TOKEN_INVALID);
        assertThat(output).doesNotContain("WARN");
    }

    @Test
    void verify_emailNotVerified_isEmailUnverified() {
        var verifier = verifierReturning(jwt(Map.of("email_verified", false)), CLIENT_ID);

        assertCode(() -> verifier.verify("t"), ErrorCode.GOOGLE_EMAIL_UNVERIFIED);
    }

    @Test
    void verify_noClientId_isDisabled() {
        var verifier = verifierReturning(jwt(Map.of()), null);

        assertThat(verifier.enabled()).isFalse();
        assertCode(() -> verifier.verify("t"), ErrorCode.GOOGLE_LOGIN_DISABLED);
    }
}
