package br.dev.duofinance.category.dto;

import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull CategoryKind kind,
        @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
        @NotNull @Pattern(regexp = "^[a-z0-9-]{1,40}$") String icon) {

    /** Surrounding whitespace is not an error; trim before validation runs. */
    public CategoryRequest {
        name = FinanceValidation.trimOrNull(name);
    }
}
