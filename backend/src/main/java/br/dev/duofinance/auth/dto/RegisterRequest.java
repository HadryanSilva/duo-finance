package br.dev.duofinance.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 60) String displayName,
        @NotNull @Pattern(regexp = "pt-BR|en") String locale,
        @AssertTrue boolean acceptTerms,
        @NotBlank String termsVersion,
        String accessCode,
        String invitationToken) {

    /** Surrounding whitespace in the e-mail is not an error; trim before validation runs. */
    public RegisterRequest {
        email = email == null ? null : email.trim();
    }
}
