package br.dev.duofinance.imports.model;

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
@Table(name = "import_batches")
public class ImportBatch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportSource source;

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportBatch() {}

    public ImportBatch(
            UUID accountId, ImportSource source, UUID bankAccountId, UUID createdBy, int rowCount, Instant now) {
        this.accountId = accountId;
        this.source = source;
        this.bankAccountId = bankAccountId;
        this.createdBy = createdBy;
        this.rowCount = rowCount;
        this.createdAt = now;
    }

    public void markUndone(Instant now) {
        this.undoneAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public ImportSource getSource() {
        return source;
    }

    public UUID getBankAccountId() {
        return bankAccountId;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public int getRowCount() {
        return rowCount;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
