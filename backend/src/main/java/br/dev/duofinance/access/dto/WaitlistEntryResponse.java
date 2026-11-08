package br.dev.duofinance.access.dto;

import br.dev.duofinance.access.model.WaitlistEntry;
import br.dev.duofinance.access.model.WaitlistStatus;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record WaitlistEntryResponse(
        UUID id,
        String email,
        String locale,
        WaitlistStatus status,
        Instant createdAt,
        @Nullable Instant decidedAt) {

    public static WaitlistEntryResponse of(WaitlistEntry e) {
        return new WaitlistEntryResponse(
                e.getId(), e.getEmail(), e.getLocale(), e.getStatus(), e.getCreatedAt(), e.getDecidedAt());
    }
}
