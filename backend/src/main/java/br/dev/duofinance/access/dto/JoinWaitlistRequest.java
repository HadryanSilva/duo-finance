package br.dev.duofinance.access.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record JoinWaitlistRequest(
        @NotBlank @Email String email,
        @NotNull @Pattern(regexp = "pt-BR|en") String locale) {

    /** Surrounding whitespace in the e-mail is not an error; trim before validation runs. */
    public JoinWaitlistRequest {
        email = email == null ? null : email.trim();
    }
}
