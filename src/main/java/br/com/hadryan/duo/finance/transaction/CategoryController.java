package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.category.CustomCategoryService;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CustomCategoryService customCategoryService;

    public CategoryController(CustomCategoryService customCategoryService) {
        this.customCategoryService = customCategoryService;
    }

    public record CategoryResponse(
            String name,          // enum name; null para categorias personalizadas
            UUID id,              // null para categorias do sistema
            String label,
            TransactionType type,
            String icon,
            boolean custom        // false = sistema, true = personalizada
    ) {}

    /**
     * GET /api/categories
     * GET /api/categories?type=EXPENSE
     * GET /api/categories?type=INCOME
     * GET /api/categories?includeCustom=false
     *
     * Retorna categorias do sistema + personalizadas ativas do casal.
     * Categorias personalizadas só são incluídas se o usuário tiver casal vinculado.
     */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "true") boolean includeCustom,
            @AuthenticationPrincipal User currentUser
    ) {
        List<CategoryResponse> result = new ArrayList<>();

        // Categorias do sistema (enum)
        List<TransactionCategory> systemCats = type != null
                ? TransactionCategory.byType(type)
                : Arrays.asList(TransactionCategory.values());

        systemCats.forEach(c -> result.add(new CategoryResponse(
                c.name(), null, c.getLabel(), c.getType(), "pi pi-circle", false
        )));

        // Categorias personalizadas — apenas se o usuário tiver casal vinculado
        if (includeCustom && currentUser != null && currentUser.getCouple() != null) {
            customCategoryService.listActive(currentUser).forEach(c -> {
                if (type == null || c.type() == type) {
                    result.add(new CategoryResponse(
                            null, c.id(), c.name(), c.type(), c.icon(), true
                    ));
                }
            });
        }

        return ResponseEntity.ok(result);
    }
}