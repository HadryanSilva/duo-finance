package br.com.hadryan.duo.finance.couple;

import br.com.hadryan.duo.finance.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "couples")
public class Couple {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    @Column(name = "invite_token", length = 64)
    private String inviteToken;

    @Column(name = "invite_expires_at")
    private LocalDateTime inviteExpiresAt;

    /** Teto global de despesas mensais do casal. Null = sem limite definido. */
    @Column(name = "global_monthly_limit", precision = 12, scale = 2)
    private BigDecimal globalMonthlyLimit;

    @OneToMany(mappedBy = "couple", fetch = FetchType.LAZY)
    private List<User> members = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    protected Couple() {}

    public Couple(String name) {
        this.name = name;
    }
}