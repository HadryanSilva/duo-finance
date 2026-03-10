package br.com.hadryan.duo.finance.user;

import br.com.hadryan.duo.finance.auth.dto.AuthDtos;
import br.com.hadryan.duo.finance.user.dto.UserDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
     * Edita firstName e lastName do usuário autenticado.
     */
    @PatchMapping("/me")
    public ResponseEntity<AuthDtos.UserInfo> updateProfile(
            @Valid @RequestBody UserDtos.UpdateProfileRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(userService.updateProfile(request, currentUser));
    }

    /**
     * POST /api/users/me/avatar
     * Recebe o arquivo de imagem, faz upload no Cloudinary e salva a URL.
     * Usa multipart/form-data com o campo "file".
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDtos.UploadAvatarResponse> uploadAvatar(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(userService.uploadAvatar(file, currentUser));
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
