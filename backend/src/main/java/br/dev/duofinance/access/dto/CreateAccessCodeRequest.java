package br.dev.duofinance.access.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateAccessCodeRequest(
        @Email String email,
        @Min(1) @Max(1000) Integer maxUses,
        Instant expiresAt,
        @Size(max = 255) String note) {

    public CreateAccessCodeRequest {
        email = email == null || email.isBlank() ? null : email.trim();
    }

    public int maxUsesOrDefault() {
        return maxUses == null ? 1 : maxUses;
    }
}
