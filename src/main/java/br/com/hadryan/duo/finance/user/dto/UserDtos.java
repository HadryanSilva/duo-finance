package br.com.hadryan.duo.finance.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserDtos {
    /**
     * PATCH /api/users/me
     * Editar nome. Email não é editável (identidade do usuário).
     */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName
    ) {}

    /**
     * Resposta após upload de avatar — devolve a nova URL para o frontend
     * atualizar o store sem precisar refazer GET /me.
     */
    public record UploadAvatarResponse(
            String avatarUrl
    ) {}
}
