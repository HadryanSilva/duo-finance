package br.dev.duofinance.transaction.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

/**
 * {@code @DynamicUpdate}: an unlocked PUT writes only the columns it changes, so it cannot write back a stale
 * {@code recurring_rule_id} or {@code created_by} after a concurrent rule or user delete nulled it.
 */
@Entity
@Table(name = "transactions")
@DynamicUpdate
public class Transaction {

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

    @Column(nullable = false)
    private LocalDate date;

    private String description;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "bank_account_id")
    private UUID bankAccountId;

    @Column(name = "destination_bank_account_id")
    private UUID destinationBankAccountId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "recurring_rule_id")
    private UUID recurringRuleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transaction() {}

    public Transaction(UUID accountId, UUID createdBy, Instant now) {
        this.accountId = accountId;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces every editable field (PUT semantics). */
    public void assign(
            TransactionKind kind,
            long amountCents,
            LocalDate date,
            String description,
            UUID categoryId,
            UUID bankAccountId,
            UUID destinationBankAccountId,
            Instant now) {
        this.kind = kind;
        this.amountCents = amountCents;
        this.date = date;
        this.description = description;
        this.categoryId = categoryId;
        this.bankAccountId = bankAccountId;
        this.destinationBankAccountId = destinationBankAccountId;
        this.updatedAt = now;
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

    public LocalDate getDate() {
        return date;
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

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getRecurringRuleId() {
        return recurringRuleId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
