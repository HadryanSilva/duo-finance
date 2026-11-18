package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.dto.CommitResponse;
import br.dev.duofinance.imports.dto.RowDecision;
import br.dev.duofinance.imports.model.ImportBatch;
import br.dev.duofinance.imports.model.ImportSource;
import br.dev.duofinance.imports.repository.CategoryMemoryRepository;
import br.dev.duofinance.imports.repository.ImportBatchRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ApiException.FieldError;
import br.dev.duofinance.transaction.model.TransactionKind;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator.ReferenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-parses and re-plans the statement, applies the user's decisions to the recomputed rows (so values always come
 * from the file) and writes the batch, transactions and category memory in one transaction.
 */
@Service
public class ImportCommitService {

    private final TransactionReferenceValidator references;
    private final ImportContextLoader contexts;
    private final ImportBatchRepository batches;
    private final ImportedTransactionWriter writer;
    private final CategoryMemoryRepository memory;
    private final Clock clock;

    public ImportCommitService(
            TransactionReferenceValidator references,
            ImportContextLoader contexts,
            ImportBatchRepository batches,
            ImportedTransactionWriter writer,
            CategoryMemoryRepository memory,
            Clock clock) {
        this.references = references;
        this.contexts = contexts;
        this.batches = batches;
        this.writer = writer;
        this.memory = memory;
        this.clock = clock;
    }

    @Transactional
    public CommitResponse commit(
            UUID accountId,
            UUID userId,
            byte[] file,
            UUID bankAccountId,
            Map<String, UUID> productMapping,
            List<RowDecision> decisions) {
        references.validateBankAccount(accountId, bankAccountId);
        if (productMapping != null) {
            productMapping.values().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(id -> references.validateBankAccount(accountId, id));
        }
        List<RevolutRow> rows = RevolutCsvParser.parse(file);
        PlanContext ctx = contexts.load(accountId, userId, bankAccountId, productMapping, rows);
        ImportPlan plan = ImportPlanner.plan(rows, ctx);

        Map<String, PlannedRow> byId = new HashMap<>();
        for (PlannedRow row : plan.rows()) {
            byId.put(row.rowId(), row);
        }
        Map<String, RowDecision> chosen = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        Map<String, String> categoryErrors = new HashMap<>();
        for (RowDecision d : decisions) {
            if (d == null || d.rowId() == null) {
                errors.add(new FieldError("decisions[]", "ROW_UNKNOWN"));
                continue;
            }
            String field = "decisions[" + d.rowId() + "]";
            PlannedRow row = byId.get(d.rowId());
            if (row == null) {
                errors.add(new FieldError(field, "ROW_UNKNOWN"));
                continue;
            }
            chosen.put(d.rowId(), d);
            if (!d.include()) {
                continue;
            }
            if (row.status() == RowStatus.ALREADY_IMPORTED) {
                errors.add(new FieldError(field, "ROW_ALREADY_IMPORTED"));
            } else if (row.kind() == TransactionKind.TRANSFER) {
                if (d.categoryId() != null) {
                    errors.add(new FieldError(field, "CATEGORY_NOT_ALLOWED"));
                }
            } else if (d.categoryId() == null) {
                errors.add(new FieldError(field, "CATEGORY_REQUIRED"));
            } else {
                String error = categoryErrors.computeIfAbsent(
                        row.kind() + ":" + d.categoryId(), k -> categoryError(accountId, row.kind(), d.categoryId()));
                if (!error.isEmpty()) {
                    errors.add(new FieldError(field, error));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.invalidFields(errors);
        }

        Instant now = Instant.now(clock);
        ImportBatch batch =
                batches.saveAndFlush(new ImportBatch(accountId, ImportSource.REVOLUT, bankAccountId, userId, 0, now));
        List<PlannedRow> toInsert = new ArrayList<>();
        Map<MemoryTarget, UUID> remembered = new LinkedHashMap<>();
        for (PlannedRow row : plan.rows()) {
            RowDecision d = chosen.get(row.rowId());
            if (d == null || !d.include()) {
                continue;
            }
            toInsert.add(row);
            if (row.kind() != TransactionKind.TRANSFER && !isPassiveDefault(row, d.categoryId(), ctx)) {
                remembered.put(new MemoryTarget(row.descriptionKey(), row.kind()), d.categoryId());
            }
        }
        // Inserted in fingerprint order, so concurrent commits of overlapping files lock the unique-index entries in
        // the same order and cannot deadlock. The category memory keeps file order (the last choice wins).
        toInsert.sort(Comparator.comparing(PlannedRow::rowId));
        int included = toInsert.size();
        int created = 0;
        for (PlannedRow row : toInsert) {
            created += writer.insert(
                    accountId, userId, batch.getId(), row, chosen.get(row.rowId()).categoryId(), now);
        }
        remembered.forEach((t, category) -> memory.upsert(accountId, t.key(), t.kind(), category, now));

        if (created == 0) {
            batches.delete(batch);
            batches.flush();
            return new CommitResponse(null, 0, included);
        }
        batch.setRowCount(created);
        batches.flush();
        return new CommitResponse(batch.getId(), created, included - created);
    }

    /** The default category is only worth remembering when the user chose it over a real suggestion. */
    private static boolean isPassiveDefault(PlannedRow row, UUID categoryId, PlanContext ctx) {
        return categoryId != null
                && categoryId.equals(ctx.defaultCategories().get(row.kind()))
                && (row.suggestionSource() == SuggestionSource.DEFAULT
                        || row.suggestionSource() == SuggestionSource.NONE);
    }

    private record MemoryTarget(String key, TransactionKind kind) {}

    /** Empty when the category is valid for the kind; otherwise the error code. */
    private String categoryError(UUID accountId, TransactionKind kind, UUID categoryId) {
        try {
            references.validate(accountId, kind, categoryId, null, null, ReferenceSnapshot.none());
            return "";
        } catch (ApiException e) {
            return e.fieldErrors().isEmpty() ? e.code().name() : e.fieldErrors().get(0).code();
        }
    }
}
