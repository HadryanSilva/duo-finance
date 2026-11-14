package br.dev.duofinance.imports.service;

import java.time.LocalDate;

public record SkippedRow(
        int lineNumber, LocalDate date, String description, String amountRaw, String currency, SkipReason reason) {}
