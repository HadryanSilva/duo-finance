package br.dev.duofinance.imports.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface MerchantCategorizer {

    /** descriptionKey -> chosen category id; keys without a confident answer are absent. Never throws. */
    Map<String, UUID> categorize(UUID accountId, List<Merchant> merchants, List<CategoryOption> categories);

    record Merchant(String descriptionKey, String description) {}

    record CategoryOption(UUID id, String name) {}
}
