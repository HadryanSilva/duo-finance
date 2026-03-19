package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "budgets")
@Table(name = "budgets", uniqueConstraints = {
        @UniqueConstraint(name = "uq_budgets_couple_category", columnNames = {"couple_id", "category"})
})
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "couple_id", nullable = false)
    private Couple couple;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionCategory category;

    /**
     * Percentual da renda mensal alocado para esta categoria.
     * Ex: 30.00 = 30% da renda mensal do casal.
     */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percentage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Budget() {}
}