package br.dev.duofinance.imports.service;

import br.dev.duofinance.account.repository.AccountRepository;
import br.dev.duofinance.goal.model.Goal;
import br.dev.duofinance.goal.model.GoalMovementType;
import br.dev.duofinance.goal.repository.GoalRepository;
import br.dev.duofinance.goal.repository.GoalSavedQuery;
import br.dev.duofinance.goal.service.GoalMovementService;
import br.dev.duofinance.goal.service.PocketNames;
import br.dev.duofinance.imports.dto.CommitResponse;
import br.dev.duofinance.imports.dto.PocketInfo;
import br.dev.duofinance.imports.dto.RowDecision;
import br.dev.duofinance.imports.model.ImportBatch;
import br.dev.duofinance.imports.model.ImportSource;
import br.dev.duofinance.imports.repository.CategoryMemoryRepository;
import br.dev.duofinance.imports.repository.ImportBatchRepository;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ApiException.FieldError;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.transaction.model.TransactionKind;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator;
import br.dev.duofinance.transaction.service.TransactionReferenceValidator.ReferenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-parses and re-plans the statement, applies the user's decisions to the recomputed rows (so values always come
 * from the file) and writes the batch, transactions, goal movements and category memory in one transaction.
 */
@Service
public class ImportCommitService {

    private final TransactionReferenceValidator references;
    private final ImportContextLoader contexts;
    private final ImportBatchRepository batches;
    private final ImportedTransactionWriter writer;
    private final CategoryMemoryRepository memory;
    private final AccountRepository accounts;
    private final GoalRepository goals;
    private final GoalSavedQuery saved;
    private final GoalMovementService goalMovements;
    private final ImportSeenRows seenRows;
    private final Clock clock;

    public ImportCommitService(
            TransactionReferenceValidator references,
            ImportContextLoader contexts,
            ImportBatchRepository batches,
            ImportedTransactionWriter writer,
            CategoryMemoryRepository memory,
            AccountRepository accounts,
            GoalRepository goals,
            GoalSavedQuery saved,
            GoalMovementService goalMovements,
            ImportSeenRows seenRows,
            Clock clock) {
        this.references = references;
        this.contexts = contexts;
        this.batches = batches;
        this.writer = writer;
        this.memory = memory;
        this.accounts = accounts;
        this.goals = goals;
        this.saved = saved;
        this.goalMovements = goalMovements;
        this.seenRows = seenRows;
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
        // Lock order of every goal write: the account row first. Held for the whole commit, so imports serialise with
        // each other and with manual goal movements, and the plan and saved amounts read below cannot go stale.
        accounts.findByIdForUpdate(accountId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
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
        Map<UUID, Goal> goalsById = decisions.stream().anyMatch(d -> d != null && d.goalId() != null)
                ? goals.findByAccountIdOrderByCreatedAtAscIdAsc(accountId).stream()
                        .collect(Collectors.toMap(Goal::getId, Function.identity()))
                : Map.of();

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
                continue;
            }
            if (row.kind() == TransactionKind.TRANSFER) {
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
            if (d.goalId() != null) {
                Goal goal = goalsById.get(d.goalId());
                if (row.pocket() == null) {
                    errors.add(new FieldError(field, "GOAL_NOT_ALLOWED"));
                } else if (goal == null || goal.isCompleted()) {
                    errors.add(new FieldError(field, "GOAL_INVALID"));
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
        List<PlannedRow> pocketMoves = new ArrayList<>();
        Map<MemoryTarget, UUID> remembered = new LinkedHashMap<>();
        for (PlannedRow row : plan.rows()) {
            RowDecision d = chosen.get(row.rowId());
            if (d == null || !d.include()) {
                continue;
            }
            // A pocket move stays in the statement's account: it is only a goal movement, never a transaction.
            if (row.pocket() != null) {
                pocketMoves.add(row);
                continue;
            }
            toInsert.add(row);
            if (row.kind() != TransactionKind.TRANSFER) {
                remembered.put(new MemoryTarget(row.descriptionKey(), row.kind()), d.categoryId());
            }
        }
        // Inserted in fingerprint order, so concurrent commits of overlapping files lock the unique-index entries in
        // the same order and cannot deadlock. The category memory keeps file order (the last choice wins).
        toInsert.sort(Comparator.comparing(PlannedRow::rowId));
        int created = 0;
        int seen = 0;
        for (PlannedRow row : toInsert) {
            if (writer.insert(accountId, userId, batch.getId(), row, chosen.get(row.rowId()).categoryId(), now) != null) {
                created++;
            }
        }
        // Rows another import stored meanwhile; a pocket move that records nothing is not one of them.
        int duplicates = toInsert.size() - created;
        // In statement order (started time, then file line): a withdrawal is clamped to what the goal held by then.
        // A move without a goal, or a withdrawal from an empty goal, records nothing.
        List<PlannedRow> goalRows = pocketMoves.stream()
                .filter(row -> chosen.get(row.rowId()).goalId() != null)
                .sorted(PlannedRow.APPLY_ORDER)
                .toList();
        for (PlannedRow row : goalRows) {
            Goal goal = goalsById.get(chosen.get(row.rowId()).goalId());
            if (recordGoalMovement(goal, userId, row, batch.getId())) {
                created++;
            } else {
                // Nothing to take from an empty goal: remember the row so a later import does not apply it.
                seenRows.remember(accountId, row.rowId(), batch.getId());
                seen++;
            }
            linkPocket(goal, row.pocket().name(), goalsById.values(), now);
        }
        remembered.forEach((t, category) -> memory.upsert(accountId, t.key(), t.kind(), category, now));

        if (created == 0 && seen == 0) {
            batches.delete(batch);
            batches.flush();
            return new CommitResponse(null, 0, duplicates);
        }
        batch.setRowCount(created);
        batches.flush();
        return new CommitResponse(batch.getId(), created, duplicates);
    }

    private record MemoryTarget(String key, TransactionKind kind) {}

    /**
     * Money into the pocket is a contribution of any size; money out is a withdrawal of at most what is saved. Returns
     * whether a movement was recorded (a withdrawal from an empty goal records none).
     */
    private boolean recordGoalMovement(Goal goal, UUID userId, PlannedRow row, UUID batchId) {
        if (row.pocket().direction() == PocketInfo.Direction.INTO) {
            goalMovements.recordImported(
                    goal, userId, GoalMovementType.CONTRIBUTION, row.amountCents(), row.date(), row.rowId(), batchId);
            return true;
        }
        long amount = Math.min(row.amountCents(), saved.savedOf(goal.getId()));
        if (amount <= 0) {
            return false;
        }
        goalMovements.recordImported(
                goal, userId, GoalMovementType.WITHDRAWAL, amount, row.date(), row.rowId(), batchId);
        return true;
    }

    /** A goal without a pocket takes the row's pocket name, unless another active goal already has it. */
    private void linkPocket(Goal goal, @Nullable String pocket, Collection<Goal> all, Instant now) {
        String name = PocketNames.clean(pocket);
        if (goal.getRevolutPocket() != null || name == null) {
            return;
        }
        String key = PocketNames.normalise(name);
        boolean taken = all.stream()
                .anyMatch(g -> !g.isCompleted()
                        && !g.getId().equals(goal.getId())
                        && g.getRevolutPocket() != null
                        && PocketNames.normalise(g.getRevolutPocket()).equals(key));
        if (!taken) {
            goal.setRevolutPocket(name);
            goal.setUpdatedAt(now);
            goals.saveAndFlush(goal);
        }
    }

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
