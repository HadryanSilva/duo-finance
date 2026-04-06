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

    /**
     * Cria uma transação.
     * Exatamente um de category ou customCategoryId deve ser informado.
     */
    public record CreateTransactionRequest(
            TransactionCategory category,       // null se customCategoryId preenchido
            UUID customCategoryId,              // null se category preenchido
            @NotNull @Positive BigDecimal amount,
            @Size(max = 255) String description,
            @NotNull LocalDate date,
            boolean recurring,
            RecurrenceRule recurrenceRule,
            LocalDate recurrenceEndDate
    ) {
        public CreateTransactionRequest {
            if (category == null && customCategoryId == null) {
                throw new IllegalArgumentException("Informe category ou customCategoryId.");
            }
            if (category != null && customCategoryId != null) {
                throw new IllegalArgumentException("Informe apenas category ou customCategoryId, não ambos.");
            }
            if (recurring && recurrenceRule == null) {
                throw new IllegalArgumentException("recurrenceRule é obrigatório quando recurring = true.");
            }
        }
    }

    public record UpdateTransactionRequest(
            TransactionCategory category,
            UUID customCategoryId,
            @NotNull @Positive BigDecimal amount,
            @Size(max = 255) String description,
            @NotNull LocalDate date
    ) {
        public UpdateTransactionRequest {
            if (category == null && customCategoryId == null) {
                throw new IllegalArgumentException("Informe category ou customCategoryId.");
            }
            if (category != null && customCategoryId != null) {
                throw new IllegalArgumentException("Informe apenas category ou customCategoryId, não ambos.");
            }
        }
    }

    /**
     * RF42 — Editar série recorrente com escolha de escopo.
     */
    public record UpdateRecurringRequest(
            TransactionCategory category,
            UUID customCategoryId,
            @NotNull @Positive BigDecimal amount,
            @Size(max = 255) String description,
            @NotNull LocalDate date,
            @NotNull RecurringScope scope
    ) {}

    /**
     * RF43 — Cancelar série recorrente com escolha de escopo.
     */
    public record DeleteRecurringRequest(
            @NotNull RecurringScope scope
    ) {}

    public enum RecurringScope {
        SINGLE,
        THIS_AND_FUTURE,
        ALL
    }

    // ── Response ──────────────────────────────────────────────────────────────

    public record TransactionResponse(
            UUID id,
            TransactionCategory category,       // null se categoria customizada
            String categoryLabel,               // sempre preenchido
            String categoryIcon,                // ícone resolvido
            UUID customCategoryId,              // null se categoria do sistema
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
            UUID customCategoryId,
            TransactionType type,
            UUID userId,
            LocalDate startDate,
            LocalDate endDate,
            String description
    ) {}

    /**
     * Representa uma série recorrente para a tela de gerenciamento.
     * Calculado a partir da transação pai (recurring=true, parentTransaction=null).
     */
    public record RecurringSeriesResponse(
            UUID id,
            TransactionCategory category,
            String categoryLabel,
            String categoryIcon,
            UUID customCategoryId,
            TransactionType type,
            BigDecimal amount,
            String description,
            LocalDate startDate,
            RecurrenceRule recurrenceRule,
            String recurrenceRuleLabel,
            LocalDate recurrenceEndDate,
            LocalDate nextOccurrence,      // próxima data ainda não gerada
            long occurrencesCount,          // total de filhos não deletados
            AuthorResponse createdBy
    ) {}
}