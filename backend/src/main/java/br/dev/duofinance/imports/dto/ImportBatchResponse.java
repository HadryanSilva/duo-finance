package br.dev.duofinance.imports.dto;

import br.dev.duofinance.imports.model.ImportSource;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code createdBy}/{@code createdByName} are null once the creating member has left. */
public record ImportBatchResponse(
        UUID id,
        ImportSource source,
        @Nullable UUID bankAccountId,
        @Nullable UUID createdBy,
        @Nullable String createdByName,
        Instant createdAt,
        int rowCount,
        @Nullable Instant undoneAt) {}
