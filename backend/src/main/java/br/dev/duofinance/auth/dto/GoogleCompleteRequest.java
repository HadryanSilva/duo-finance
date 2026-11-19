package br.dev.duofinance.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record GoogleCompleteRequest(
        @NotBlank String signupTicket,
        @NotBlank @Size(max = 60) String displayName,
        @NotNull @Pattern(regexp = "pt-BR|en") String locale,
        @AssertTrue boolean acceptTerms,
        @NotBlank @Size(max = 50) String termsVersion,
        @Nullable String accessCode,
        @Nullable String invitationToken) {}
