package br.dev.duofinance.category.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The kind cannot change after creation, so it is not part of the update. */
public record UpdateCategoryRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
        @NotNull @Pattern(regexp = "^[a-z0-9-]{1,40}$") String icon) {

    public UpdateCategoryRequest {
        name = FinanceValidation.trimOrNull(name);
    }
}
