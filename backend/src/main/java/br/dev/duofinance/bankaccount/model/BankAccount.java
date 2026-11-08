package br.dev.duofinance.bankaccount.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BankAccountType type;

    @Column(name = "initial_balance_cents", nullable = false)
    private long initialBalanceCents;

    @Column(name = "holder_user_id")
    private UUID holderUserId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BankAccount() {}

    public BankAccount(
            UUID accountId,
            String name,
            BankAccountType type,
            long initialBalanceCents,
            UUID holderUserId,
            Instant now) {
        this.accountId = accountId;
        this.name = name;
        this.type = type;
        this.initialBalanceCents = initialBalanceCents;
        this.holderUserId = holderUserId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BankAccountType getType() {
        return type;
    }

    public void setType(BankAccountType type) {
        this.type = type;
    }

    public long getInitialBalanceCents() {
        return initialBalanceCents;
    }

    public void setInitialBalanceCents(long initialBalanceCents) {
        this.initialBalanceCents = initialBalanceCents;
    }

    public UUID getHolderUserId() {
        return holderUserId;
    }

    public void setHolderUserId(UUID holderUserId) {
        this.holderUserId = holderUserId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
