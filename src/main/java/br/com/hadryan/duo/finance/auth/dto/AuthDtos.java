package br.com.hadryan.duo.finance.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class AuthDtos {
    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            UserInfo user
    ) {}

    public record UserInfo(
            String id,
            String firstName,
            String lastName,
            String email,
            String avatarUrl,
            String coupleId
    ) {}
}
