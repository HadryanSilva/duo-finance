package br.dev.duofinance.transaction.dto;

/**
 * Sums of the transactions matching the list's filters. Transfers only count when the filter names a bank account:
 * into it as income, out of it as expense. {@code count} is every matched row, transfers included.
 */
public record TransactionTotalsResponse(long incomeCents, long expenseCents, long netCents, long count) {}
