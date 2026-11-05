package br.dev.duofinance.access.model;

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
@Table(name = "waitlist_entries")
public class WaitlistEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status = WaitlistStatus.PENDING;

    @Column(name = "access_code_id")
    private UUID accessCodeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected WaitlistEntry() {}

    public WaitlistEntry(String email, String locale, Instant now) {
        this.email = email;
        this.locale = locale;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getLocale() {
        return locale;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public void setStatus(WaitlistStatus status) {
        this.status = status;
    }

    public UUID getAccessCodeId() {
        return accessCodeId;
    }

    public void setAccessCodeId(UUID accessCodeId) {
        this.accessCodeId = accessCodeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
