package br.dev.duofinance.imports.service;

import br.dev.duofinance.shared.token.SecureTokens;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic per-row fingerprints used to deduplicate re-imported statements. */
public final class RowFingerprints {

    private RowFingerprints() {}

    /** Fingerprints in input order; identical rows are told apart by their 0-based occurrence index. */
    public static List<String> of(List<RevolutRow> rows) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> result = new ArrayList<>(rows.size());
        for (RevolutRow row : rows) {
            String base = "REVOLUT|" + row.startedRaw() + "|" + row.amountRaw() + "|" + row.currency() + "|"
                    + row.description() + "|" + row.product();
            int n = seen.merge(base, 1, Integer::sum) - 1;
            result.add(SecureTokens.sha256Hex(base + "|" + n));
        }
        return result;
    }

    public static String fee(String rowFingerprint) {
        return SecureTokens.sha256Hex(rowFingerprint + ":fee");
    }
}
