package br.dev.duofinance.imports.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises Revolut pocket moves. The savings product is matched by name and never aliased, so stored fingerprints
 * keep the product exactly as the file spells it. Pocket-name wordings live here, one per language: the pt-PT one
 * comes from a real statement; the English ones follow what other Revolut parsers report.
 */
public final class PocketRows {

    public static final Set<String> SAVINGS_PRODUCTS = Set.of("Poupanças", "Savings");

    /** Lower-cased: older English exports spell types in capitals ({@code TRANSFER}). */
    private static final Set<String> TRANSFER_TYPES = Set.of("transferência", "transfer");

    private static final String CURRENT = "Current";
    private static final Pattern PT_PT = Pattern.compile("^Carregamento de subconta [A-Z]{3} (.+) de [A-Z]{3}$");
    private static final List<Pattern> ENGLISH = List.of(
            Pattern.compile("^To pocket [A-Z]{3} (.+) from [A-Z]{3}$"),
            Pattern.compile("^From pocket [A-Z]{3} (.+) to [A-Z]{3}$"));
    /** An English move out of a pocket that does not name it: the review asks for the goal. */
    private static final String ENGLISH_WITHDRAWAL = "Pocket Withdrawal";

    private PocketRows() {}

    public static Optional<String> pocketName(String description) {
        Matcher pt = PT_PT.matcher(description);
        if (pt.matches()) return Optional.of(pt.group(1));
        for (Pattern pattern : ENGLISH) {
            Matcher m = pattern.matcher(description);
            if (m.matches()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    public static boolean isSavingsProduct(String product) {
        return SAVINGS_PRODUCTS.contains(product);
    }

    public static boolean isPocketRow(RevolutRow row) {
        if (isSavingsProduct(row.product()))
            return TRANSFER_TYPES.contains(row.type().toLowerCase(Locale.ROOT));
        return CURRENT.equals(row.product())
                && (pocketName(row.description()).isPresent() || ENGLISH_WITHDRAWAL.equals(row.description()));
    }

    /** The savings product name in the language of the wording: the file's own spelling of the missing leg. */
    public static String savingsProductFor(String description) {
        return PT_PT.matcher(description).matches() ? "Poupanças" : "Savings";
    }
}
