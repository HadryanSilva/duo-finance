package br.com.hadryan.duo.finance.category.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

public class CustomCategoryDtos {

    // ── Requests ──────────────────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank(message = "Nome é obrigatório")
            @Size(max = 60, message = "Nome deve ter no máximo 60 caracteres")
            String name,

            @NotNull(message = "Tipo é obrigatório")
            TransactionType type,

            @Size(max = 60)
            String icon   // ex: "pi pi-star" — opcional, padrão no backend
    ) {}

    public record UpdateRequest(
            @NotBlank(message = "Nome é obrigatório")
            @Size(max = 60, message = "Nome deve ter no máximo 60 caracteres")
            String name,

            @Size(max = 60)
            String icon
    ) {}

    // ── Response ──────────────────────────────────────────────────────────────

    public record CustomCategoryResponse(
            UUID id,
            String name,
            TransactionType type,
            String icon,
            boolean active,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}
}
