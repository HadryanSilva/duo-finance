package br.com.hadryan.duo.finance.report;

import br.com.hadryan.duo.finance.report.dto.CategorySumProjection;
import br.com.hadryan.duo.finance.report.dto.MonthlySumProjection;
import br.com.hadryan.duo.finance.report.dto.ReportDtos;
import br.com.hadryan.duo.finance.shared.exception.BusinessException;
import br.com.hadryan.duo.finance.transaction.Transaction;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import br.com.hadryan.duo.finance.user.UserRepository;
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

    private final ReportRepository reportRepository;
    private final UserRepository   userRepository;

    // ── Summary ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.SummaryResponse summary(LocalDate startDate, LocalDate endDate, User currentUser) {
        UUID coupleId = requireCoupleId(currentUser);

        BigDecimal totalIncome  = reportRepository.sumByType(coupleId, TransactionType.INCOME,  startDate, endDate);
        BigDecimal totalExpense = reportRepository.sumByType(coupleId, TransactionType.EXPENSE, startDate, endDate);
        BigDecimal balance      = totalIncome.subtract(totalExpense);
        long count              = reportRepository.countInPeriod(coupleId, startDate, endDate);

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
                reportRepository.sumByCategory(coupleId, type, startDate, endDate);

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
                            Math.round(pct * 100.0) / 100.0
                    );
                })
                .toList();

        return new ReportDtos.ByCategoryResponse(startDate, endDate, type, total, categories);
    }

    // ── Monthly Comparison ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.MonthlyComparisonResponse monthlyComparison(User currentUser) {
        UUID coupleId = requireCoupleId(currentUser);

        LocalDate endDate   = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate startDate = LocalDate.now().minusMonths(5).withDayOfMonth(1);

        List<MonthlySumProjection> projections =
                reportRepository.sumByMonth(coupleId, startDate, endDate);

        Map<String, Map<TransactionType, BigDecimal>> index = projections.stream()
                .collect(Collectors.groupingBy(
                        p -> p.year() + "-" + p.month(),
                        Collectors.toMap(MonthlySumProjection::type, MonthlySumProjection::total)
                ));

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
                    cursor.getYear(), cursor.getMonthValue(), label,
                    income, expense, income.subtract(expense)
            ));
            cursor = cursor.plusMonths(1);
        }

        return new ReportDtos.MonthlyComparisonResponse(months);
    }

    // ── CSV Export ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String exportCsv(LocalDate startDate, LocalDate endDate, User currentUser) {
        UUID coupleId = requireCoupleId(currentUser);
        List<Transaction> transactions = reportRepository.findAllForExport(coupleId, startDate, endDate);

        StringBuilder csv = new StringBuilder();
        csv.append("Data,Tipo,Categoria,Descrição,Valor,Lançado por\n");

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

    // ── RF39: Partner Comparison ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReportDtos.PartnerComparisonResponse partnerComparison(
            LocalDate startDate, LocalDate endDate, User currentUser
    ) {
        UUID coupleId = requireCoupleId(currentUser);

        List<User> members = userRepository.findByCoupleId(coupleId);

        if (members.size() < 2) {
            throw new BusinessException("O comparativo entre parceiros requer dois membros na conta.");
        }

        User p1 = members.get(0);
        User p2 = members.get(1);

        return new ReportDtos.PartnerComparisonResponse(
                startDate,
                endDate,
                buildPartnerSummary(p1, coupleId, startDate, endDate),
                buildPartnerSummary(p2, coupleId, startDate, endDate)
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReportDtos.PartnerSummary buildPartnerSummary(
            User user, UUID coupleId, LocalDate startDate, LocalDate endDate
    ) {
        UUID userId = user.getId();

        BigDecimal income  = reportRepository.sumByTypeAndUser(coupleId, userId, TransactionType.INCOME,  startDate, endDate);
        BigDecimal expense = reportRepository.sumByTypeAndUser(coupleId, userId, TransactionType.EXPENSE, startDate, endDate);

        List<CategorySumProjection> categoryProjections =
                reportRepository.sumByCategoryAndUser(coupleId, userId, TransactionType.EXPENSE, startDate, endDate);

        BigDecimal totalExpense = categoryProjections.stream()
                .map(CategorySumProjection::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ReportDtos.CategoryBreakdown> topCategories = categoryProjections.stream()
                .limit(5)
                .map(p -> {
                    double pct = totalExpense.compareTo(BigDecimal.ZERO) == 0 ? 0.0
                            : p.total().divide(totalExpense, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .doubleValue();
                    return new ReportDtos.CategoryBreakdown(
                            p.category(),
                            p.category().getLabel(),
                            p.total(),
                            Math.round(pct * 100.0) / 100.0
                    );
                })
                .toList();

        return new ReportDtos.PartnerSummary(
                userId,
                user.getFirstName(),
                user.getLastName(),
                user.getAvatarUrl(),
                income,
                expense,
                income.subtract(expense),
                topCategories
        );
    }

    private UUID requireCoupleId(User user) {
        if (user.getCouple() == null) {
            throw new BusinessException("Você ainda não pertence a nenhuma conta de casal.");
        }
        return user.getCouple().getId();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\n") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}