package br.dev.duofinance.category.dto;

import br.dev.duofinance.category.model.Category;
import br.dev.duofinance.category.model.CategoryKind;
import java.util.UUID;

public record CategoryResponse(
        UUID id, String name, CategoryKind kind, String color, String icon, boolean systemDefault, boolean archived) {

    public static CategoryResponse of(Category c) {
        return new CategoryResponse(
                c.getId(),
                c.getName(),
                c.getKind(),
                c.getColor(),
                c.getIcon(),
                c.isSystemDefault(),
                c.getArchivedAt() != null);
    }
}
