package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.auth.jwt.JwtService;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
public class AuthController {

    private final RefreshTokenService   refreshTokenService;
    private final JwtService            jwtService;
    private final AuthService           authService;
    private final PasswordResetService  passwordResetService;

    /** POST /api/auth/register */
    @PostMapping("/api/auth/register")
    public ResponseEntity<AuthDtos.TokenResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /** POST /api/auth/login */
    @PostMapping("/api/auth/login")
    public ResponseEntity<AuthDtos.TokenResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request
    ) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** POST /auth/refresh */
    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthDtos.TokenResponse> refresh(
            @Valid @RequestBody AuthDtos.RefreshRequest request
    ) {
        RefreshToken newRefresh  = refreshTokenService.rotate(request.refreshToken());
        User         user        = newRefresh.getUser();
        String       accessToken = jwtService.generateAccessToken(user);
        return ResponseEntity.ok(buildTokenResponse(accessToken, newRefresh.getToken(), user));
    }

    /** POST /auth/logout */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(401).build();
        refreshTokenService.revokeAllForUser(user.getId());
        return ResponseEntity.noContent().build();
    }

    // ── RF26 — Redefinição de senha ───────────────────────────────────────────

    /**
     * POST /api/auth/forgot-password
     * Envia e-mail com link de redefinição. Sempre retorna 204 (sem enumerar e-mails).
     */
    @PostMapping("/api/auth/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody AuthDtos.ForgotPasswordRequest request
    ) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/auth/reset-password
     * Valida o token e define a nova senha.
     */
    @PostMapping("/api/auth/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody AuthDtos.ResetPasswordRequest request
    ) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthDtos.TokenResponse buildTokenResponse(String accessToken, String refreshToken, User user) {
        return new AuthDtos.TokenResponse(accessToken, refreshToken, toUserInfo(user));
    }

    private AuthDtos.UserInfo toUserInfo(User user) {
        return new AuthDtos.UserInfo(
                user.getId().toString(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getCouple() != null ? user.getCouple().getId().toString() : null
        );
    }
}