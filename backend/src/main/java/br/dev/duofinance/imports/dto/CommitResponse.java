package br.dev.duofinance.imports.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code batchId} is null when nothing was created. */
public record CommitResponse(@Nullable UUID batchId, int created, int skippedDuplicates) {}
