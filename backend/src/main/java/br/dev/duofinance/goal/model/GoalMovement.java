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

    /** The imported transaction this movement mirrors, if any. */
    @Column(name = "transaction_id")
    private @Nullable UUID transactionId;

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
            @Nullable UUID transactionId,
            @Nullable String note,
            @Nullable UUID createdBy,
            Instant now) {
        this.occurredOn = occurredOn;
        this.transactionId = transactionId;
        this.goalId = goalId;
        this.accountId = accountId;
        this.type = type;
        this.amountCents = amountCents;
        this.month = month;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** The mirrored transaction changed: take its amount and date, and count a contribution in the new month. */
    public void followTransaction(long amountCents, LocalDate occurredOn) {
        this.amountCents = amountCents;
        this.occurredOn = occurredOn;
        if (type == GoalMovementType.CONTRIBUTION) {
            this.month = occurredOn.withDayOfMonth(1);
        }
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

    public @Nullable UUID getTransactionId() {
        return transactionId;
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
