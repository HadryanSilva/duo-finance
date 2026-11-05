package br.dev.duofinance.access.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Only the SHA-256 of the normalized code is stored; the plain code is shown once at generation. */
@Entity
@Table(name = "access_codes")
public class AccessCode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    private String email;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    private String note;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccessCode() {}

    public AccessCode(
            String codeHash, String email, int maxUses, Instant expiresAt, String note, UUID createdBy, Instant now) {
        this.codeHash = codeHash;
        this.email = email;
        this.maxUses = maxUses;
        this.expiresAt = expiresAt;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** True when the code can be redeemed by the given normalized e-mail at the given instant. */
    public boolean isRedeemableBy(String normalizedEmail, Instant now) {
        return revokedAt == null
                && usedCount < maxUses
                && (expiresAt == null || expiresAt.isAfter(now))
                && (email == null || email.equals(normalizedEmail));
    }

    public UUID getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getEmail() {
        return email;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public void incrementUsedCount() {
        this.usedCount++;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getNote() {
        return note;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
