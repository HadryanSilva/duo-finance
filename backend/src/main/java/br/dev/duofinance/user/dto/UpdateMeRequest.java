package br.dev.duofinance.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: a null field is left unchanged. */
public record UpdateMeRequest(
        @Size(max = 60) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String displayName,

        @Pattern(regexp = "pt-BR|en") String locale) {}
