package br.dev.duofinance.goal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "goals")
public class Goal {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "target_cents", nullable = false)
    private long targetCents;

    @Column(name = "target_date")
    private @Nullable LocalDate targetDate;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "monthly_percent", nullable = false)
    private int monthlyPercent;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Column(name = "revolut_pocket", length = 100)
    private @Nullable String revolutPocket;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Goal() {}

    public Goal(
            UUID accountId,
            String name,
            long targetCents,
            @Nullable LocalDate targetDate,
            int monthlyPercent,
            Instant now) {
        this.accountId = accountId;
        this.name = name;
        this.targetCents = targetCents;
        this.targetDate = targetDate;
        this.monthlyPercent = monthlyPercent;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isCompleted() {
        return completedAt != null;
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

    public long getTargetCents() {
        return targetCents;
    }

    public void setTargetCents(long targetCents) {
        this.targetCents = targetCents;
    }

    public @Nullable LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(@Nullable LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public int getMonthlyPercent() {
        return monthlyPercent;
    }

    public void setMonthlyPercent(int monthlyPercent) {
        this.monthlyPercent = monthlyPercent;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(@Nullable Instant completedAt) {
        this.completedAt = completedAt;
    }

    public @Nullable String getRevolutPocket() {
        return revolutPocket;
    }

    public void setRevolutPocket(@Nullable String revolutPocket) {
        this.revolutPocket = revolutPocket;
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
