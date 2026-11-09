package br.dev.duofinance.access.dto;

import br.dev.duofinance.access.model.AccessCode;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record AccessCodeResponse(
        UUID id,
        @Nullable String email,
        int maxUses,
        int usedCount,
        @Nullable Instant expiresAt,
        @Nullable Instant revokedAt,
        @Nullable String note,
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
