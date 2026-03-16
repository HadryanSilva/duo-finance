package br.com.hadryan.duo.finance.goal.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class GoalDtos {

    // ── Request ───────────────────────────────────────────────────────────────

    public record CreateGoalRequest(
            @NotNull(message = "Categoria é obrigatória")
            TransactionCategory category,

            @NotNull(message = "Limite mensal é obrigatório")
            @DecimalMin(value = "0.01", message = "Limite deve ser maior que zero")
            BigDecimal monthlyLimit
    ) {}

    public record UpdateGoalRequest(
            @NotNull(message = "Limite mensal é obrigatório")
            @DecimalMin(value = "0.01", message = "Limite deve ser maior que zero")
            BigDecimal monthlyLimit
    ) {}

    // ── Response ──────────────────────────────────────────────────────────────

    public record GoalResponse(
            UUID id,
            TransactionCategory category,
            String categoryLabel,
            BigDecimal monthlyLimit,
            boolean active,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    // ── Progress — RF36/RF37 ──────────────────────────────────────────────────

    public record GoalProgressResponse(
            UUID id,
            TransactionCategory category,
            String categoryLabel,
            BigDecimal monthlyLimit,
            BigDecimal spent,
            BigDecimal remaining,
            double percentage,
            AlertLevel alertLevel,
            boolean active
    ) {}

    public enum AlertLevel {
        NONE,     // < 80%
        WARNING,  // >= 80% e < 100%
        EXCEEDED  // >= 100%
    }
}