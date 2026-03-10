package br.com.hadryan.duo.finance.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record ForgotPasswordRequest(
            @NotBlank @Email String email
    ) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {}
}
