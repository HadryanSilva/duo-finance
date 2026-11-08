package br.dev.duofinance.transaction.dto;

import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionFilter(
        LocalDate from,
        LocalDate to,
        TransactionKind kind,
        UUID categoryId,
        UUID bankAccountId,
        UUID createdBy,
        String q) {}
