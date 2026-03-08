package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    public record CategoryResponse(
            String name,
            String label,
            TransactionType type
    ) {}

    /**
     * GET /api/categories
     * GET /api/categories?type=EXPENSE
     * GET /api/categories?type=INCOME
     */
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(
            @RequestParam(required = false) TransactionType type
    ) {
        List<TransactionCategory> categories = type != null
                ? TransactionCategory.byType(type)
                : Arrays.asList(TransactionCategory.values());

        List<CategoryResponse> response = categories.stream()
                .map(c -> new CategoryResponse(c.name(), c.getLabel(), c.getType()))
                .toList();

        return ResponseEntity.ok(response);
    }
}



