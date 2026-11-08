package br.dev.duofinance.shared.finance;

import java.time.LocalDate;

public final class FinanceValidation {

    public static final long MAX_AMOUNT_CENTS = 100_000_000_000L;
    public static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

    private FinanceValidation() {}

    /** Trims; a blank result becomes {@code null}. */
    public static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
