package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.budget.dto.BudgetDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/budget")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    /**
     * GET /api/budget/overview
     * GET /api/budget/overview?year=2026&month=3
     */
    @GetMapping("/overview")
    public ResponseEntity<BudgetDtos.BudgetOverviewResponse> overview(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @AuthenticationPrincipal User currentUser
    ) {
        int y = year  != null ? year  : LocalDate.now().getYear();
        int m = month != null ? month : LocalDate.now().getMonthValue();
        return ResponseEntity.ok(budgetService.overview(y, m, currentUser));
    }

    /**
     * GET /api/budget/allocations
     * Lista as alocações atuais com valores calculados.
     */
    @GetMapping("/allocations")
    public ResponseEntity<List<BudgetDtos.BudgetAllocationResponse>> listAllocations(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(budgetService.listAllocations(currentUser));
    }

    /**
     * PUT /api/budget/income
     * Define ou atualiza a renda mensal do casal.
     */
    @PutMapping("/income")
    public ResponseEntity<Void> setIncome(
            @Valid @RequestBody BudgetDtos.SetIncomeRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        budgetService.setIncome(request, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/budget/income
     * Remove a renda mensal.
     */
    @DeleteMapping("/income")
    public ResponseEntity<Void> removeIncome(@AuthenticationPrincipal User currentUser) {
        budgetService.removeIncome(currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * PUT /api/budget
     * Salva as alocações do orçamento (cria ou atualiza por categoria).
     * A soma dos percentuais não pode exceder 100%.
     */
    @PutMapping
    public ResponseEntity<List<BudgetDtos.BudgetAllocationResponse>> saveBudget(
            @Valid @RequestBody BudgetDtos.SaveBudgetRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(budgetService.saveBudget(request, currentUser));
    }

    /**
     * DELETE /api/budget/category/{category}
     * Remove uma categoria do orçamento.
     */
    @DeleteMapping("/category/{category}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable TransactionCategory category,
            @AuthenticationPrincipal User currentUser
    ) {
        budgetService.deleteCategory(category, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/budget
     * Limpa todo o orçamento do casal.
     */
    @DeleteMapping
    public ResponseEntity<Void> clearAll(@AuthenticationPrincipal User currentUser) {
        budgetService.clearAll(currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/budget/comparison?months=6
     */
    @GetMapping("/comparison")
    public ResponseEntity<BudgetDtos.BudgetComparisonResponse> comparison(
            @RequestParam(defaultValue = "6") int months,
            @AuthenticationPrincipal User currentUser
    ) {
        if (months < 1 || months > 24) months = 6;
        return ResponseEntity.ok(budgetService.comparison(months, currentUser));
    }
}