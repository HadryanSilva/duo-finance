package br.dev.duofinance.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(@NotBlank @Email String email) {

    public ForgotPasswordRequest {
        email = email == null ? null : email.trim();
    }
}
