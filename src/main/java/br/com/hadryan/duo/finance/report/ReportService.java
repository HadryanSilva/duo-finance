package br.com.hadryan.duo.finance.report;

import br.com.hadryan.duo.finance.report.dto.CategorySumProjection;
import br.com.hadryan.duo.finance.report.dto.MonthlySumProjection;
import br.com.hadryan.duo.finance.report.dto.ReportDtos;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.transaction.Transaction;
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

@RequiredArgsConstructor
@Service
public class ReportService {

    private final ReportRepository repository;

    // ── Summary ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.SummaryResponse summary(LocalDate startDate, LocalDate endDate, User currentUser) {
        UUID coupleId = requireCoupleId(currentUser);

        BigDecimal totalIncome  = repository.sumByType(coupleId, TransactionType.INCOME,  startDate, endDate);
        BigDecimal totalExpense = repository.sumByType(coupleId, TransactionType.EXPENSE, startDate, endDate);
        BigDecimal balance      = totalIncome.subtract(totalExpense);
        long count              = repository.countInPeriod(coupleId, startDate, endDate);

        return new ReportDtos.SummaryResponse(startDate, endDate, totalIncome, totalExpense, balance, count);
    }

    // ── By Category ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.ByCategoryResponse byCategory(
            LocalDate startDate, LocalDate endDate,
            TransactionType type, User currentUser
    ) {
        UUID coupleId = requireCoupleId(currentUser);

        List<CategorySumProjection> projections =
                repository.sumByCategory(coupleId, type, startDate, endDate);

        BigDecimal total = projections.stream()
                .map(CategorySumProjection::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ReportDtos.CategoryBreakdown> categories = projections.stream()
                .map(p -> {
                    double pct = total.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : p.total().divide(total, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .doubleValue();
                    return new ReportDtos.CategoryBreakdown(
                            p.category(),
                            p.category().getLabel(),
                            p.total(),
                            Math.round(pct * 100.0) / 100.0   // 2 casas decimais
                    );
                })
                .toList();

        return new ReportDtos.ByCategoryResponse(startDate, endDate, type, total, categories);
    }

    // ── Monthly Comparison ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.MonthlyComparisonResponse monthlyComparison(User currentUser) {
        UUID coupleId = requireCoupleId(currentUser);

        // Últimos 6 meses completos + mês atual
        LocalDate endDate   = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate startDate = LocalDate.now().minusMonths(5).withDayOfMonth(1);

        List<MonthlySumProjection> projections =
                repository.sumByMonth(coupleId, startDate, endDate);

        // Indexa por "ano-mês" para lookup rápido
        Map<String, Map<TransactionType, BigDecimal>> index = projections.stream()
                .collect(Collectors.groupingBy(
                        p -> p.year() + "-" + p.month(),
                        Collectors.toMap(
                                MonthlySumProjection::type,
                                MonthlySumProjection::total
                        )
                ));

        // Gera a lista dos 6 meses garantindo todos os meses, mesmo os sem dados
        List<ReportDtos.MonthSummary> months = new ArrayList<>();
        LocalDate cursor = startDate;
        Locale ptBR = new Locale("pt", "BR");

        while (!cursor.isAfter(endDate)) {
            String key    = cursor.getYear() + "-" + cursor.getMonthValue();
            Map<TransactionType, BigDecimal> byType = index.getOrDefault(key, Map.of());

            BigDecimal income  = byType.getOrDefault(TransactionType.INCOME,  BigDecimal.ZERO);
            BigDecimal expense = byType.getOrDefault(TransactionType.EXPENSE, BigDecimal.ZERO);

            String label = cursor.getMonth().getDisplayName(TextStyle.SHORT, ptBR)
                    + "/" + String.valueOf(cursor.getYear()).substring(2);

            months.add(new ReportDtos.MonthSummary(
                    cursor.getYear(),
                    cursor.getMonthValue(),
                    label,
                    income,
                    expense,
                    income.subtract(expense)
            ));

            cursor = cursor.plusMonths(1);
        }

        return new ReportDtos.MonthlyComparisonResponse(months);
    }

    // ── CSV Export ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String exportCsv(LocalDate startDate, LocalDate endDate, User currentUser) {
        UUID coupleId = requireCoupleId(currentUser);

        List<Transaction> transactions = repository.findAllForExport(coupleId, startDate, endDate);

        StringBuilder csv = new StringBuilder();

        // Cabeçalho
        csv.append("Data,Tipo,Categoria,Descrição,Valor,Lançado por\n");

        // Linhas
        for (Transaction t : transactions) {
            csv.append(t.getDate()).append(",")
                    .append(t.getType()).append(",")
                    .append(t.getCategory().getLabel()).append(",")
                    .append(escapeCsv(t.getDescription())).append(",")
                    .append(t.getAmount()).append(",")
                    .append(t.getUser().getFirstName()).append(" ")
                    .append(t.getUser().getLastName()).append("\n");
        }

        return csv.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID requireCoupleId(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple().getId();
    }

    /** Envolve o valor em aspas se contiver vírgula ou quebra de linha. */
    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\n") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}