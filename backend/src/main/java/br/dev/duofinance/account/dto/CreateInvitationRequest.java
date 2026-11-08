package br.dev.duofinance.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateInvitationRequest(@NotBlank @Email String email) {

    /** Surrounding whitespace in the e-mail is not an error; trim before validation runs. */
    public CreateInvitationRequest {
        email = email == null ? null : email.trim();
    }
}
