package br.dev.duofinance.imports.dto;

import br.dev.duofinance.imports.service.ImportPlan;
import br.dev.duofinance.imports.service.PlannedRow;
import br.dev.duofinance.imports.service.SkipReason;
import br.dev.duofinance.imports.service.SkippedRow;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PreviewResponse(List<ProductMapping> products, List<PlannedRow> rows, Skipped skipped) {

    public record ProductMapping(String product, @Nullable UUID bankAccountId) {}

    public record Skipped(Map<SkipReason, Integer> counts, List<SkippedRow> rows) {}

    /** {@code mapping} is the effective product to bank account assignment; unmapped products show null. */
    public static PreviewResponse of(ImportPlan plan, Map<String, UUID> mapping) {
        List<ProductMapping> products = plan.products().stream()
                .map(p -> new ProductMapping(p, mapping.get(p)))
                .toList();
        Map<SkipReason, Integer> counts = new EnumMap<>(SkipReason.class);
        for (SkippedRow row : plan.skipped()) {
            counts.merge(row.reason(), 1, Integer::sum);
        }
        return new PreviewResponse(products, plan.rows(), new Skipped(counts, plan.skipped()));
    }
}
