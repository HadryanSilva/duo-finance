package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.budget.dto.BudgetDtos;
import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.couple.CoupleRepository;
import br.com.hadryan.duo.finance.report.ReportRepository;
import br.com.hadryan.duo.finance.report.dto.CategorySumProjection;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BudgetService {

    private final CoupleRepository  coupleRepository;
    private final BudgetRepository  budgetRepository;
    private final ReportRepository  reportRepository;

    // ── Renda mensal ──────────────────────────────────────────────────────────

    @Transactional
    public void setIncome(BudgetDtos.SetIncomeRequest request, User currentUser) {
        Couple couple = requireCouple(currentUser);
        couple.setMonthlyIncome(request.monthlyIncome());
        coupleRepository.save(couple);
    }

    @Transactional
    public void removeIncome(User currentUser) {
        Couple couple = requireCouple(currentUser);
        couple.setMonthlyIncome(null);
        coupleRepository.save(couple);
    }

    // ── Salvar alocações ──────────────────────────────────────────────────────

    /**
     * Salva (cria ou atualiza) as alocações do orçamento.
     * Valida que a soma dos percentuais não ultrapasse 100%.
     * Não exige exatamente 100% — o usuário pode ter parte da renda não alocada.
     */
    @Transactional
    public List<BudgetDtos.BudgetAllocationResponse> saveBudget(
            BudgetDtos.SaveBudgetRequest request, User currentUser) {

        Couple couple = requireCouple(currentUser);
        requireIncome(couple);

        BigDecimal totalPct = request.allocations().stream()
                .map(BudgetDtos.CategoryAllocationRequest::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPct.compareTo(new BigDecimal("100.00")) > 0) {
            throw new BusinessException(
                    "A soma dos percentuais não pode exceder 100%. Soma atual: "
                            + totalPct.setScale(2, RoundingMode.HALF_UP) + "%");
        }

        BigDecimal income = couple.getMonthlyIncome();

        List<Budget> toSave = request.allocations().stream().map(alloc -> {
            Budget budget = budgetRepository
                    .findByCoupleIdAndCategory(couple.getId(), alloc.category())
                    .orElseGet(() -> {
                        Budget b = new Budget();
                        b.setCouple(couple);
                        b.setCategory(alloc.category());
                        return b;
                    });
            budget.setPercentage(alloc.percentage());
            return budget;
        }).toList();

        budgetRepository.saveAll(toSave);

        return toAllocationList(toSave, income);
    }

    @Transactional
    public void deleteCategory(TransactionCategory category, User currentUser) {
        Couple couple = requireCouple(currentUser);
        budgetRepository.deleteByCoupleIdAndCategory(couple.getId(), category);
    }

    @Transactional
    public void clearAll(User currentUser) {
        Couple couple = requireCouple(currentUser);
        budgetRepository.deleteByCoupleId(couple.getId());
    }

    // ── Visão geral do orçamento ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BudgetDtos.BudgetOverviewResponse overview(int year, int month, User currentUser) {
        Couple couple   = requireCouple(currentUser);
        UUID   coupleId = couple.getId();
        BigDecimal income = couple.getMonthlyIncome();

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());

        List<Budget> budgets = budgetRepository.findByCoupleId(coupleId);

        List<CategorySumProjection> spentByCategory =
                reportRepository.sumByCategory(coupleId, TransactionType.EXPENSE, start, end);

        Map<TransactionCategory, BigDecimal> spentMap = spentByCategory.stream()
                .collect(Collectors.toMap(CategorySumProjection::category, CategorySumProjection::total));

        BigDecimal totalAllocatedPct = budgets.stream()
                .map(Budget::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAllocated = income != null
                ? income.multiply(totalAllocatedPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal totalSpent = spentMap.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRemaining = totalAllocated.subtract(totalSpent);

        List<BudgetDtos.CategoryBudgetItem> categories = budgets.stream()
                .map(b -> {
                    BigDecimal allocated = income != null
                            ? income.multiply(b.getPercentage())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;

                    BigDecimal spent     = spentMap.getOrDefault(b.getCategory(), BigDecimal.ZERO);
                    BigDecimal remaining = allocated.subtract(spent);

                    double usagePct = allocated.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : spent.divide(allocated, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();

                    return new BudgetDtos.CategoryBudgetItem(
                            b.getCategory(),
                            b.getCategory().getLabel(),
                            b.getPercentage(),
                            allocated,
                            spent,
                            remaining,
                            usagePct,
                            resolveStatus(usagePct)
                    );
                })
                .sorted(Comparator.comparing(BudgetDtos.CategoryBudgetItem::allocated).reversed())
                .toList();

        String monthLabel = start.getMonth()
                .getDisplayName(TextStyle.FULL, new Locale("pt", "BR")) + "/" + year;

        return new BudgetDtos.BudgetOverviewResponse(
                income, totalAllocated, totalAllocatedPct,
                totalSpent, totalRemaining,
                year, month, monthLabel, categories
        );
    }

    // ── Comparação orçado vs realizado ────────────────────────────────────────

    @Transactional(readOnly = true)
    public BudgetDtos.BudgetComparisonResponse comparison(int months, User currentUser) {
        Couple couple   = requireCouple(currentUser);
        UUID   coupleId = couple.getId();
        BigDecimal income = couple.getMonthlyIncome();

        List<Budget> budgets = budgetRepository.findByCoupleId(coupleId);
        BigDecimal totalPct = budgets.stream()
                .map(Budget::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAllocated = income != null
                ? income.multiply(totalPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<BudgetDtos.MonthComparison> result = new ArrayList<>();
        LocalDate cursor = LocalDate.now().withDayOfMonth(1).minusMonths(months - 1L);
        Locale ptBR = new Locale("pt", "BR");

        while (!cursor.isAfter(LocalDate.now())) {
            LocalDate start = cursor;
            LocalDate end   = cursor.withDayOfMonth(cursor.lengthOfMonth());
            BigDecimal spent = reportRepository.sumByType(coupleId, TransactionType.EXPENSE, start, end);
            BigDecimal balance = totalAllocated.subtract(spent);

            double adherence = totalAllocated.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                    : spent.divide(totalAllocated, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();

            String label = cursor.getMonth().getDisplayName(TextStyle.SHORT, ptBR)
                    + "/" + String.valueOf(cursor.getYear()).substring(2);

            result.add(new BudgetDtos.MonthComparison(
                    cursor.getYear(), cursor.getMonthValue(), label,
                    totalAllocated, spent, balance,
                    Math.round(adherence * 100.0) / 100.0,
                    spent.compareTo(totalAllocated) <= 0
            ));
            cursor = cursor.plusMonths(1);
        }

        return new BudgetDtos.BudgetComparisonResponse(income, result);
    }

    // ── Listar alocações atuais ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BudgetDtos.BudgetAllocationResponse> listAllocations(User currentUser) {
        Couple couple = requireCouple(currentUser);
        List<Budget> budgets = budgetRepository.findByCoupleId(couple.getId());
        return toAllocationList(budgets, couple.getMonthlyIncome());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<BudgetDtos.BudgetAllocationResponse> toAllocationList(
            List<Budget> budgets, BigDecimal income) {
        return budgets.stream()
                .map(b -> {
                    BigDecimal allocated = income != null
                            ? income.multiply(b.getPercentage())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new BudgetDtos.BudgetAllocationResponse(
                            b.getCategory(), b.getCategory().getLabel(),
                            b.getPercentage(), allocated);
                })
                .sorted(Comparator.comparing(BudgetDtos.BudgetAllocationResponse::percentage).reversed())
                .toList();
    }

    private BudgetDtos.BudgetStatus resolveStatus(double pct) {
        if (pct > 100.0) return BudgetDtos.BudgetStatus.EXCEEDED;
        if (pct > 80.0)  return BudgetDtos.BudgetStatus.WARNING;
        return BudgetDtos.BudgetStatus.OK;
    }

    private Couple requireCouple(User user) {
        if (user.getCouple() == null)
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        return coupleRepository.findById(user.getCouple().getId())
                .orElseThrow(() -> new BusinessException("Conta do casal não encontrada."));
    }

    private void requireIncome(Couple couple) {
        if (couple.getMonthlyIncome() == null)
            throw new BusinessException("Informe a renda mensal do casal antes de definir o orçamento.");
    }
}