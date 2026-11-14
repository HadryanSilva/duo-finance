package br.dev.duofinance.imports.service;

import java.time.LocalDateTime;

/**
 * One data row of a Revolut CSV statement. {@code startedRaw} and {@code amountRaw} keep the exact (trimmed) file text
 * because the import fingerprint hashes them; {@code completedAt} is null while the row is still pending.
 */
public record RevolutRow(
        int lineNumber,
        String type,
        String product,
        LocalDateTime startedAt,
        String startedRaw,
        LocalDateTime completedAt,
        String description,
        long amountCents,
        String amountRaw,
        long feeCents,
        String currency,
        String state) {}
