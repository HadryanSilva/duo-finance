package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.budget.dto.BudgetDtos;
import br.com.hadryan.duo.finance.couple.Couple;
import br.com.hadryan.duo.finance.couple.CoupleRepository;
import br.com.hadryan.duo.finance.goal.Goal;
import br.com.hadryan.duo.finance.goal.GoalRepository;
import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
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
    private final GoalRepository    goalRepository;
    private final ReportRepository  reportRepository;

    // ── Definir / remover limite global ──────────────────────────────────────

    @Transactional
    public void setGlobalLimit(BudgetDtos.SetGlobalLimitRequest request, User currentUser) {
        Couple couple = requireCouple(currentUser);
        couple.setGlobalMonthlyLimit(request.monthlyLimit());
        coupleRepository.save(couple);
    }

    @Transactional
    public void removeGlobalLimit(User currentUser) {
        Couple couple = requireCouple(currentUser);
        couple.setGlobalMonthlyLimit(null);
        coupleRepository.save(couple);
    }

    // ── Visão geral do orçamento ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BudgetDtos.BudgetOverviewResponse overview(int year, int month, User currentUser) {
        Couple couple   = requireCouple(currentUser);
        UUID   coupleId = couple.getId();

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());

        List<Goal> activeGoals = goalRepository.findActiveByCoupleId(coupleId);
        List<CategorySumProjection> spentByCategory =
                reportRepository.sumByCategory(coupleId, TransactionType.EXPENSE, start, end);

        Map<TransactionCategory, BigDecimal> spentMap = spentByCategory.stream()
                .collect(Collectors.toMap(CategorySumProjection::category, CategorySumProjection::total));

        BigDecimal totalBudgeted = activeGoals.stream()
                .map(Goal::getMonthlyLimit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSpent = spentMap.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal globalLimit    = couple.getGlobalMonthlyLimit();
        BigDecimal effectiveLimit = globalLimit != null ? globalLimit : totalBudgeted;
        BigDecimal totalRemaining = effectiveLimit.subtract(totalSpent).max(BigDecimal.ZERO);

        double globalPercentage = effectiveLimit.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                : totalSpent.divide(effectiveLimit, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        List<BudgetDtos.CategoryBudgetItem> categories = activeGoals.stream()
                .map(goal -> {
                    BigDecimal spent     = spentMap.getOrDefault(goal.getCategory(), BigDecimal.ZERO);
                    BigDecimal budgeted  = goal.getMonthlyLimit();
                    BigDecimal remaining = budgeted.subtract(spent).max(BigDecimal.ZERO);

                    double pct = budgeted.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : spent.divide(budgeted, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();

                    double pctOfTotal = totalSpent.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : spent.divide(totalSpent, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();

                    return new BudgetDtos.CategoryBudgetItem(
                            goal.getCategory(), goal.getCategory().getLabel(),
                            budgeted, spent, remaining,
                            Math.min(pct, 100.0),
                            Math.round(pctOfTotal * 100.0) / 100.0,
                            resolveAlert(pct)
                    );
                })
                .sorted(Comparator.comparing(BudgetDtos.CategoryBudgetItem::spent).reversed())
                .toList();

        String monthLabel = start.getMonth()
                .getDisplayName(TextStyle.FULL, new Locale("pt", "BR")) + "/" + year;

        return new BudgetDtos.BudgetOverviewResponse(
                year, month, monthLabel,
                globalLimit, totalBudgeted, totalSpent, totalRemaining,
                Math.min(globalPercentage, 100.0), resolveAlert(globalPercentage),
                categories
        );
    }

    // ── Comparação orçado vs realizado ────────────────────────────────────────

    @Transactional(readOnly = true)
    public BudgetDtos.BudgetComparisonResponse comparison(int months, User currentUser) {
        Couple couple   = requireCouple(currentUser);
        UUID   coupleId = couple.getId();

        List<Goal> activeGoals = goalRepository.findActiveByCoupleId(coupleId);
        BigDecimal totalBudgeted = activeGoals.stream()
                .map(Goal::getMonthlyLimit).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal effectiveLimit = couple.getGlobalMonthlyLimit() != null
                ? couple.getGlobalMonthlyLimit() : totalBudgeted;

        List<BudgetDtos.MonthComparison> result = new ArrayList<>();
        LocalDate cursor = LocalDate.now().withDayOfMonth(1).minusMonths(months - 1L);
        Locale ptBR = new Locale("pt", "BR");

        while (!cursor.isAfter(LocalDate.now())) {
            LocalDate start = cursor;
            LocalDate end   = cursor.withDayOfMonth(cursor.lengthOfMonth());
            BigDecimal spent = reportRepository.sumByType(coupleId, TransactionType.EXPENSE, start, end);
            BigDecimal balance = effectiveLimit.subtract(spent);

            double adherence = effectiveLimit.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                    : spent.divide(effectiveLimit, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();

            String label = cursor.getMonth().getDisplayName(TextStyle.SHORT, ptBR)
                    + "/" + String.valueOf(cursor.getYear()).substring(2);

            result.add(new BudgetDtos.MonthComparison(
                    cursor.getYear(), cursor.getMonthValue(), label,
                    effectiveLimit, spent, balance,
                    Math.round(adherence * 100.0) / 100.0,
                    spent.compareTo(effectiveLimit) <= 0
            ));
            cursor = cursor.plusMonths(1);
        }

        return new BudgetDtos.BudgetComparisonResponse(result);
    }

    // ── Distribuição automática ───────────────────────────────────────────────

    @Transactional
    public BudgetDtos.DistributeResponse distribute(
            BudgetDtos.DistributeRequest request, User currentUser) {

        Couple couple = requireCouple(currentUser);
        requireGlobalLimit(couple);

        BigDecimal globalLimit = couple.getGlobalMonthlyLimit();
        List<Goal> activeGoals = goalRepository.findActiveByCoupleId(couple.getId());
        requireGoals(activeGoals);

        Map<TransactionCategory, BigDecimal> allocations = switch (request.rule()) {
            case RULE_50_30_20           -> distribute503020(activeGoals, globalLimit);
            case PROPORTIONAL_HISTORICAL -> distributeProportional(activeGoals, couple.getId(), globalLimit);
            case EQUAL                   -> distributeEqual(activeGoals, globalLimit);
        };

        applyAllocations(activeGoals, allocations);

        List<BudgetDtos.CategoryAllocation> allocationList = buildAllocationList(activeGoals, allocations, globalLimit);
        return new BudgetDtos.DistributeResponse(request.rule(), globalLimit, allocationList);
    }

    // ── Distribuição customizada ──────────────────────────────────────────────

    @Transactional
    public BudgetDtos.CustomDistributeResponse distributeCustom(
            BudgetDtos.CustomDistributeRequest request, User currentUser) {

        Couple couple = requireCouple(currentUser);
        requireGlobalLimit(couple);

        BigDecimal globalLimit = couple.getGlobalMonthlyLimit();
        List<Goal> activeGoals = goalRepository.findActiveByCoupleId(couple.getId());
        requireGoals(activeGoals);

        // Valida que a soma dos percentuais é exatamente 100
        BigDecimal total = request.allocations().stream()
                .map(BudgetDtos.CategoryPercentage::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (total.compareTo(new BigDecimal("100.00")) != 0) {
            throw new BusinessException(
                    "A soma dos percentuais deve ser exatamente 100%. Soma atual: " +
                            total.setScale(2, RoundingMode.HALF_UP) + "%");
        }

        // Valida que todas as categorias informadas têm meta ativa
        Set<TransactionCategory> activeCategories = activeGoals.stream()
                .map(Goal::getCategory).collect(Collectors.toSet());

        request.allocations().forEach(alloc -> {
            if (!activeCategories.contains(alloc.category())) {
                throw new BusinessException(
                        "A categoria " + alloc.category().getLabel() +
                                " não possui uma meta ativa. Crie a meta antes de distribuir.");
            }
        });

        // Monta mapa de alocações: category → valor em reais
        Map<TransactionCategory, BigDecimal> allocations = request.allocations().stream()
                .collect(Collectors.toMap(
                        BudgetDtos.CategoryPercentage::category,
                        alloc -> globalLimit
                                .multiply(alloc.percentage())
                                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                ));

        applyAllocations(activeGoals, allocations);

        List<BudgetDtos.CategoryAllocation> allocationList = buildAllocationList(activeGoals, allocations, globalLimit);
        return new BudgetDtos.CustomDistributeResponse(globalLimit, allocationList);
    }

    // ── Estratégias de distribuição ───────────────────────────────────────────

    private Map<TransactionCategory, BigDecimal> distribute503020(
            List<Goal> goals, BigDecimal globalLimit) {

        Set<TransactionCategory> necessidades = Set.of(
                TransactionCategory.HOUSING, TransactionCategory.FOOD,
                TransactionCategory.HEALTH,  TransactionCategory.TRANSPORT,
                TransactionCategory.SUPERMARKET
        );
        Set<TransactionCategory> poupanca = Set.of(TransactionCategory.INVESTMENTS);

        List<Goal> goalsNec  = goals.stream().filter(g -> necessidades.contains(g.getCategory())).toList();
        List<Goal> goalsPoup = goals.stream().filter(g -> poupanca.contains(g.getCategory())).toList();
        List<Goal> goalsDes  = goals.stream()
                .filter(g -> !necessidades.contains(g.getCategory()) && !poupanca.contains(g.getCategory()))
                .toList();

        Map<TransactionCategory, BigDecimal> result = new HashMap<>();
        allocatePool(goalsNec,  globalLimit.multiply(new BigDecimal("0.50")), result);
        allocatePool(goalsDes,  globalLimit.multiply(new BigDecimal("0.30")), result);
        allocatePool(goalsPoup, globalLimit.multiply(new BigDecimal("0.20")), result);
        return result;
    }

    private Map<TransactionCategory, BigDecimal> distributeProportional(
            List<Goal> goals, UUID coupleId, BigDecimal globalLimit) {

        LocalDate end   = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate start = LocalDate.now().minusMonths(2).withDayOfMonth(1);

        List<CategorySumProjection> historical =
                reportRepository.sumByCategory(coupleId, TransactionType.EXPENSE, start, end);

        Set<TransactionCategory> goalCategories = goals.stream()
                .map(Goal::getCategory).collect(Collectors.toSet());

        BigDecimal totalHistorical = historical.stream()
                .filter(p -> goalCategories.contains(p.category()))
                .map(CategorySumProjection::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalHistorical.compareTo(BigDecimal.ZERO) == 0) return distributeEqual(goals, globalLimit);

        Map<TransactionCategory, BigDecimal> result = new HashMap<>();
        goals.forEach(goal -> {
            BigDecimal hist = historical.stream()
                    .filter(p -> p.category() == goal.getCategory())
                    .map(CategorySumProjection::total)
                    .findFirst().orElse(BigDecimal.ZERO);

            BigDecimal share = hist.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : hist.divide(totalHistorical, 4, RoundingMode.HALF_UP)
                    .multiply(globalLimit).setScale(2, RoundingMode.HALF_UP);
            result.put(goal.getCategory(), share);
        });
        return result;
    }

    private Map<TransactionCategory, BigDecimal> distributeEqual(
            List<Goal> goals, BigDecimal globalLimit) {
        BigDecimal perCategory = globalLimit.divide(BigDecimal.valueOf(goals.size()), 2, RoundingMode.HALF_UP);
        Map<TransactionCategory, BigDecimal> result = new HashMap<>();
        goals.forEach(g -> result.put(g.getCategory(), perCategory));
        return result;
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    private void allocatePool(List<Goal> goals, BigDecimal pool,
                              Map<TransactionCategory, BigDecimal> result) {
        if (goals.isEmpty()) return;
        BigDecimal perGoal = pool.divide(BigDecimal.valueOf(goals.size()), 2, RoundingMode.HALF_UP);
        goals.forEach(g -> result.put(g.getCategory(), perGoal));
    }

    private void applyAllocations(List<Goal> goals, Map<TransactionCategory, BigDecimal> allocations) {
        goals.forEach(goal -> {
            BigDecimal allocated = allocations.getOrDefault(goal.getCategory(), BigDecimal.ZERO);
            if (allocated.compareTo(BigDecimal.ZERO) > 0) goal.setMonthlyLimit(allocated);
        });
        goalRepository.saveAll(goals);
    }

    private List<BudgetDtos.CategoryAllocation> buildAllocationList(
            List<Goal> goals, Map<TransactionCategory, BigDecimal> allocations, BigDecimal globalLimit) {
        return goals.stream()
                .map(goal -> {
                    BigDecimal allocated = allocations.getOrDefault(goal.getCategory(), BigDecimal.ZERO);
                    double pct = globalLimit.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : allocated.divide(globalLimit, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();
                    return new BudgetDtos.CategoryAllocation(
                            goal.getCategory(), goal.getCategory().getLabel(),
                            allocated, Math.round(pct * 100.0) / 100.0);
                })
                .sorted(Comparator.comparing(BudgetDtos.CategoryAllocation::allocated).reversed())
                .toList();
    }

    private GoalDtos.AlertLevel resolveAlert(double percentage) {
        if (percentage >= 100.0) return GoalDtos.AlertLevel.EXCEEDED;
        if (percentage >= 80.0)  return GoalDtos.AlertLevel.WARNING;
        return GoalDtos.AlertLevel.NONE;
    }

    private Couple requireCouple(User user) {
        if (user.getCouple() == null)
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        return coupleRepository.findById(user.getCouple().getId())
                .orElseThrow(() -> new BusinessException("Conta do casal não encontrada."));
    }

    private void requireGlobalLimit(Couple couple) {
        if (couple.getGlobalMonthlyLimit() == null)
            throw new BusinessException("Defina um limite global mensal antes de distribuir o orçamento.");
    }

    private void requireGoals(List<Goal> goals) {
        if (goals.isEmpty())
            throw new BusinessException("Crie ao menos uma meta de categoria antes de distribuir o orçamento.");
    }
}