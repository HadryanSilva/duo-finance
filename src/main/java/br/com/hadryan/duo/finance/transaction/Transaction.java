package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.category.CustomCategory;
import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.transaction.enums.RecurrenceRule;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "transactions")
@Table(name = "transactions", indexes = {
        @Index(name = "idx_tx_couple_date",     columnList = "couple_id, date"),
        @Index(name = "idx_tx_couple_category", columnList = "couple_id, category")
})
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "couple_id")
    private Couple couple;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * Categoria do sistema (enum). Nulo quando customCategory está preenchido.
     * Exatamente um dos dois campos deve estar preenchido por transação.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private TransactionCategory category;

    /**
     * Categoria personalizada do casal. Nulo quando category (enum) está preenchido.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "custom_category_id")
    private CustomCategory customCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "is_recurring", nullable = false)
    private boolean recurring = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_rule", length = 20)
    private RecurrenceRule recurrenceRule;

    @Column(name = "recurrence_end_date")
    private LocalDate recurrenceEndDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_transaction_id")
    private Transaction parentTransaction;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // ── Helpers de conveniência ───────────────────────────────────────────────

    /** Label resolvido independente da fonte da categoria. */
    public String resolvedLabel() {
        if (customCategory != null) return customCategory.getName();
        if (category != null)       return category.getLabel();
        return "Sem categoria";
    }

    /** Ícone resolvido. */
    public String resolvedIcon() {
        if (customCategory != null) return customCategory.getIcon();
        return "pi pi-circle";
    }
}