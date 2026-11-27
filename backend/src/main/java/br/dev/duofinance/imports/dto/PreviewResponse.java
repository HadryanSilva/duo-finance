package br.dev.duofinance.imports.dto;

import br.dev.duofinance.imports.service.ImportPlan;
import br.dev.duofinance.imports.service.PlannedRow;
import br.dev.duofinance.imports.service.PocketRows;
import br.dev.duofinance.imports.service.RowStatus;
import br.dev.duofinance.imports.service.SkipReason;
import br.dev.duofinance.imports.service.SkippedRow;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PreviewResponse(List<ProductMapping> products, List<PreviewRow> rows, Skipped skipped) {

    public record ProductMapping(String product, @Nullable UUID bankAccountId) {}

    public record PreviewRow(
            String rowId,
            LocalDate date,
            TransactionKind kind,
            long amountCents,
            String description,
            String descriptionKey,
            UUID bankAccountId,
            @Nullable UUID destinationBankAccountId,
            @Nullable UUID suggestedCategoryId,
            RowStatus status,
            @Nullable PocketInfo pocket,
            /** Position in the order the commit applies rows (statement time, then file line), from 0. */
            int applyOrder) {

        static PreviewRow of(PlannedRow row, int applyOrder) {
            return new PreviewRow(
                    row.rowId(),
                    row.date(),
                    row.kind(),
                    row.amountCents(),
                    row.description(),
                    row.descriptionKey(),
                    row.bankAccountId(),
                    row.destinationBankAccountId(),
                    row.suggestedCategoryId(),
                    row.status(),
                    row.pocket(),
                    applyOrder);
        }
    }

    public record PreviewSkippedRow(
            int lineNumber, LocalDate date, String description, String amountRaw, String currency, SkipReason reason) {

        static PreviewSkippedRow of(SkippedRow row) {
            return new PreviewSkippedRow(
                    row.lineNumber(), row.date(), row.description(), row.amountRaw(), row.currency(), row.reason());
        }
    }

    public record Skipped(Map<SkipReason, Integer> counts, List<PreviewSkippedRow> rows) {}

    /** {@code mapping} is the effective product to bank account assignment; unmapped products show null. */
    public static PreviewResponse of(ImportPlan plan, Map<String, UUID> mapping) {
        // Savings products have no mapping to choose: their rows belong to the statement's own account.
        List<ProductMapping> products = plan.products().stream()
                .filter(p -> !PocketRows.isSavingsProduct(p))
                .map(p -> new ProductMapping(p, mapping.get(p)))
                .toList();
        List<PlannedRow> applied =
                plan.rows().stream().sorted(PlannedRow.APPLY_ORDER).toList();
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < applied.size(); i++) {
            order.put(applied.get(i).rowId(), i);
        }
        Map<SkipReason, Integer> counts = new EnumMap<>(SkipReason.class);
        for (SkippedRow row : plan.skipped()) {
            counts.merge(row.reason(), 1, Integer::sum);
        }
        return new PreviewResponse(
                products,
                plan.rows().stream()
                        .map(r -> PreviewRow.of(r, order.get(r.rowId())))
                        .toList(),
                new Skipped(
                        counts,
                        plan.skipped().stream().map(PreviewSkippedRow::of).toList()));
    }
}
