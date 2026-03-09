package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * GET /api/users/me
     * Retorna os dados do usuário autenticado pelo JWT atual.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthDtos.UserInfo> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(new AuthDtos.UserInfo(
                user.getId().toString(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getCouple() != null ? user.getCouple().getId().toString() : null
        ));
    }
}
