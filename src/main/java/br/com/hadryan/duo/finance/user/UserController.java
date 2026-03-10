package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.user.dto.UserDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * GET /api/users/me
     * Retorna os dados do usuário autenticado pelo JWT atual.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserInfo> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(toUserInfo(user));
    }

    /**
     * PATCH /api/users/me
     * Atualiza nome e/ou avatar do usuário autenticado.
     */
    @PatchMapping("/me")
    public ResponseEntity<AuthDtos.UserInfo> updateMe(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UserDtos.UpdateProfileRequest request
    ) {
        User updated = userService.updateProfile(user, request);
        return ResponseEntity.ok(toUserInfo(updated));
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
