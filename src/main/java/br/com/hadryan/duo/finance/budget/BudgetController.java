package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.budget.dto.BudgetDtos;
import br.com.hadryan.duo.finance.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/budget")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    /**
     * GET /api/budget/overview
     * GET /api/budget/overview?year=2026&month=3
     * Visão consolidada do orçamento do mês.
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
     * PUT /api/budget/global-limit
     * Define ou atualiza o teto global mensal do casal.
     */
    @PutMapping("/global-limit")
    public ResponseEntity<Void> setGlobalLimit(
            @Valid @RequestBody BudgetDtos.SetGlobalLimitRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        budgetService.setGlobalLimit(request, currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/budget/global-limit
     * Remove o limite global (volta a usar a soma das metas).
     */
    @DeleteMapping("/global-limit")
    public ResponseEntity<Void> removeGlobalLimit(
            @AuthenticationPrincipal User currentUser
    ) {
        budgetService.removeGlobalLimit(currentUser);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/budget/distribute
     * Distribui automaticamente o limite global entre as metas ativas.
     */
    @PostMapping("/distribute")
    public ResponseEntity<BudgetDtos.DistributeResponse> distribute(
            @Valid @RequestBody BudgetDtos.DistributeRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(budgetService.distribute(request, currentUser));
    }

    /**
     * GET /api/budget/comparison?months=6
     * Comparação orçado vs realizado dos últimos N meses.
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