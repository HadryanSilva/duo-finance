package br.dev.duofinance.recurring.model;

import br.dev.duofinance.transaction.model.TransactionKind;
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

@Entity
@Table(name = "recurring_rules")
public class RecurringRule {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionKind kind;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    private String description;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "bank_account_id")
    private UUID bankAccountId;

    @Column(name = "destination_bank_account_id")
    private UUID destinationBankAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecurringRule() {}

    /** A new active rule whose first run is its start date. Kind, frequency and start date never change. */
    public RecurringRule(
            UUID accountId,
            UUID createdBy,
            TransactionKind kind,
            Frequency frequency,
            LocalDate startDate,
            Instant now) {
        this.accountId = accountId;
        this.createdBy = createdBy;
        this.kind = kind;
        this.frequency = frequency;
        this.startDate = startDate;
        this.nextRunDate = startDate;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces every editable field (PUT semantics). */
    public void assign(
            long amountCents,
            String description,
            UUID categoryId,
            UUID bankAccountId,
            UUID destinationBankAccountId,
            LocalDate endDate,
            Instant now) {
        this.amountCents = amountCents;
        this.description = description;
        this.categoryId = categoryId;
        this.bankAccountId = bankAccountId;
        this.destinationBankAccountId = destinationBankAccountId;
        this.endDate = endDate;
        this.updatedAt = now;
    }

    public void activate(LocalDate nextRunDate, Instant now) {
        this.active = true;
        this.nextRunDate = nextRunDate;
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    public void setNextRunDate(LocalDate nextRunDate) {
        this.nextRunDate = nextRunDate;
    }

    /** No occurrence is left: the next run falls after the end date. */
    public boolean isFinished() {
        return endDate != null && nextRunDate.isAfter(endDate);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public TransactionKind getKind() {
        return kind;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public UUID getBankAccountId() {
        return bankAccountId;
    }

    public UUID getDestinationBankAccountId() {
        return destinationBankAccountId;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getNextRunDate() {
        return nextRunDate;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
