package br.dev.duofinance.imports.service;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises Revolut pocket moves. The savings product is matched by name and never aliased, so stored fingerprints
 * keep the product exactly as the file spells it. Pocket-name wordings live here, one per language.
 */
public final class PocketRows {

    public static final Set<String> SAVINGS_PRODUCTS = Set.of("Poupanças", "Savings");

    private static final Set<String> TRANSFER_TYPES = Set.of("Transferência", "Transfer");
    private static final String CURRENT = "Current";
    private static final Pattern PT_PT = Pattern.compile("^Carregamento de subconta [A-Z]{3} (.+) de [A-Z]{3}$");

    private PocketRows() {}

    public static Optional<String> pocketName(String description) {
        Matcher m = PT_PT.matcher(description);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static boolean isSavingsProduct(String product) {
        return SAVINGS_PRODUCTS.contains(product);
    }

    public static boolean isPocketRow(RevolutRow row) {
        if (isSavingsProduct(row.product())) return TRANSFER_TYPES.contains(row.type());
        return CURRENT.equals(row.product()) && pocketName(row.description()).isPresent();
    }

    /** The savings product name in the language of the wording: the file's own spelling of the missing leg. */
    public static String savingsProductFor(String description) {
        return PT_PT.matcher(description).matches() ? "Poupanças" : "Savings";
    }
}
