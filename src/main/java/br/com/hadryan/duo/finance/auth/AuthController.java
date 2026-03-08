package br.com.hadryan.duo.finance.auth;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.auth.jwt.JwtService;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    /**
     * POST /auth/refresh
     * Troca um refresh token válido por um novo par access + refresh (rotação).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.TokenResponse> refresh(
            @Valid @RequestBody AuthDtos.RefreshRequest request
    ) {
        RefreshToken newRefresh = refreshTokenService.rotate(request.refreshToken());
        User user = newRefresh.getUser();

        String newAccessToken = jwtService.generateAccessToken(user);

        return ResponseEntity.ok(buildTokenResponse(newAccessToken, newRefresh.getToken(), user));
    }

    /**
     * POST /auth/logout
     * Revoga todos os refresh tokens do usuário autenticado.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal User user) {
        refreshTokenService.revokeAllForUser(user.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /auth/me
     * Retorna os dados do usuário autenticado pelo JWT atual.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserInfo> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(toUserInfo(user));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthDtos.TokenResponse buildTokenResponse(
            String accessToken, String refreshToken, User user
    ) {
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

