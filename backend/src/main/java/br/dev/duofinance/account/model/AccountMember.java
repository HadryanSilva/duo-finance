package br.dev.duofinance.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Links a user to the single account they belong to ({@code user_id} is unique). */
@Entity
@Table(name = "account_members")
@IdClass(AccountMember.Key.class)
public class AccountMember {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected AccountMember() {}

    public AccountMember(UUID accountId, UUID userId, Instant now) {
        this.accountId = accountId;
        this.userId = userId;
        this.joinedAt = now;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public record Key(UUID accountId, UUID userId) implements Serializable {}
}
