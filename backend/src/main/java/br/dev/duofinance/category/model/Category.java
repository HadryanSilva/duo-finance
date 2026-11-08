package br.dev.duofinance.category.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

/**
 * {@code @DynamicUpdate}: an unlocked PUT writes only the columns it changes, so it cannot undo a concurrent archive,
 * unarchive or reference change committed between its read and its flush.
 */
@Entity
@Table(name = "categories")
@DynamicUpdate
public class Category {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CategoryKind kind;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private String icon;

    /** True for categories seeded at registration. */
    @Column(name = "system_default", nullable = false)
    private boolean systemDefault;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {}

    public Category(
            UUID accountId,
            String name,
            CategoryKind kind,
            String color,
            String icon,
            boolean systemDefault,
            Instant now) {
        this.accountId = accountId;
        this.name = name;
        this.kind = kind;
        this.color = color;
        this.icon = icon;
        this.systemDefault = systemDefault;
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

    public CategoryKind getKind() {
        return kind;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public boolean isSystemDefault() {
        return systemDefault;
    }

    public Instant getArchivedAt() {
        return archivedAt;
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
