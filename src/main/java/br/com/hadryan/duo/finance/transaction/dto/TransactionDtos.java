package br.com.hadryan.duo.finance.transaction.dto;

import br.com.hadryan.duo.finance.transaction.enums.RecurrenceRule;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class TransactionDtos {

    // ── Requests ──────────────────────────────────────────────────────────────

    public record CreateTransactionRequest(
            @NotNull TransactionCategory category,
            @NotNull @Positive BigDecimal amount,
            @Size(max = 255) String description,
            @NotNull LocalDate date,
            boolean recurring,
            RecurrenceRule recurrenceRule,
            LocalDate recurrenceEndDate
    ) {
        // Validação de negócio no compact constructor
        public CreateTransactionRequest {
            if (recurring && recurrenceRule == null) {
                throw new IllegalArgumentException(
                        "recurrenceRule é obrigatório quando recurring = true");
            }
        }
    }

    public record UpdateTransactionRequest(
            @NotNull TransactionCategory category,
            @NotNull @Positive BigDecimal amount,
            @Size(max = 255) String description,
            @NotNull LocalDate date
    ) {}

    // ── Response ──────────────────────────────────────────────────────────────

    public record TransactionResponse(
            UUID id,
            TransactionCategory category,
            String categoryLabel,
            TransactionType type,
            BigDecimal amount,
            String description,
            LocalDate date,
            boolean recurring,
            RecurrenceRule recurrenceRule,
            LocalDate recurrenceEndDate,
            UUID parentTransactionId,
            AuthorResponse createdBy,
            LocalDateTime createdAt
    ) {}

    public record AuthorResponse(
            UUID id,
            String firstName,
            String lastName,
            String avatarUrl
    ) {}

    // ── Filtros ───────────────────────────────────────────────────────────────

    public record TransactionFilter(
            TransactionCategory category,
            TransactionType type,
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    ) {}
}
