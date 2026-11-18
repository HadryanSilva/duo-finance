package br.dev.duofinance.imports.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NoOpMerchantCategorizer implements MerchantCategorizer {

    @Override
    public Map<String, UUID> categorize(UUID accountId, List<Merchant> merchants, List<CategoryOption> categories) {
        return Map.of();
    }
}
