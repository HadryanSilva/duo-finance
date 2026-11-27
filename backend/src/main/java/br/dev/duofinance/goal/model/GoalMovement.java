package br.dev.duofinance.goal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "goal_movements")
public class GoalMovement {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "goal_id", nullable = false)
    private UUID goalId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoalMovementType type;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    /** First day of the month a CONTRIBUTION or SKIP refers to; null for WITHDRAWAL. */
    @Column
    private @Nullable LocalDate month;

    /** The day the money was set aside (or taken out). */
    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    /** The statement row this movement was imported from (its fingerprint), if any. */
    @Column(name = "import_fingerprint")
    private @Nullable String importFingerprint;

    /** The import batch that recorded this movement, if any; undoing the batch removes it. */
    @Column(name = "import_batch_id")
    private @Nullable UUID importBatchId;

    /** A CONTRIBUTION that superseded its month's SKIP: removing the month's last contribution puts the skip back. */
    @Column(name = "replaced_skip", nullable = false)
    private boolean replacedSkip;

    @Column(length = 200)
    private @Nullable String note;

    @Column(name = "created_by")
    private @Nullable UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GoalMovement() {}

    public GoalMovement(
            UUID goalId,
            UUID accountId,
            GoalMovementType type,
            long amountCents,
            @Nullable LocalDate month,
            LocalDate occurredOn,
            @Nullable String note,
            @Nullable UUID createdBy,
            Instant now) {
        this.occurredOn = occurredOn;
        this.goalId = goalId;
        this.accountId = accountId;
        this.type = type;
        this.amountCents = amountCents;
        this.month = month;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void setReplacedSkip(boolean replacedSkip) {
        this.replacedSkip = replacedSkip;
    }

    public boolean replacedSkip() {
        return replacedSkip;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public GoalMovementType getType() {
        return type;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public @Nullable LocalDate getMonth() {
        return month;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    /** Marks the movement as imported from {@code fingerprint} by {@code batchId}. */
    public void importedFrom(String fingerprint, UUID batchId) {
        this.importFingerprint = fingerprint;
        this.importBatchId = batchId;
    }

    public @Nullable String getImportFingerprint() {
        return importFingerprint;
    }

    public @Nullable UUID getImportBatchId() {
        return importBatchId;
    }

    public @Nullable String getNote() {
        return note;
    }

    public @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
