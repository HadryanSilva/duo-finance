package br.dev.duofinance.access.dto;

import br.dev.duofinance.access.model.AccessCode;
import java.time.Instant;
import java.util.UUID;

public record AccessCodeResponse(
        UUID id,
        String email,
        int maxUses,
        int usedCount,
        Instant expiresAt,
        Instant revokedAt,
        String note,
        Instant createdAt) {

    public static AccessCodeResponse of(AccessCode c) {
        return new AccessCodeResponse(
                c.getId(),
                c.getEmail(),
                c.getMaxUses(),
                c.getUsedCount(),
                c.getExpiresAt(),
                c.getRevokedAt(),
                c.getNote(),
                c.getCreatedAt());
    }
}
