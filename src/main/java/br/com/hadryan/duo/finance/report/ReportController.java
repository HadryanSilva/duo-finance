package br.com.hadryan.duo.finance.report;

import br.com.hadryan.duo.finance.report.dto.ReportDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import br.com.hadryan.duo.finance.user.User;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** GET /api/reports/summary */
    @GetMapping("/summary")
    public ResponseEntity<ReportDtos.SummaryResponse> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal User currentUser
    ) {
        LocalDate[] range = resolveRange(startDate, endDate);
        return ResponseEntity.ok(reportService.summary(range[0], range[1], currentUser));
    }

    /** GET /api/reports/by-category?type=EXPENSE */
    @GetMapping("/by-category")
    public ResponseEntity<ReportDtos.ByCategoryResponse> byCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "EXPENSE") TransactionType type,
            @AuthenticationPrincipal User currentUser
    ) {
        LocalDate[] range = resolveRange(startDate, endDate);
        return ResponseEntity.ok(reportService.byCategory(range[0], range[1], type, currentUser));
    }

    /** GET /api/reports/monthly-comparison */
    @GetMapping("/monthly-comparison")
    public ResponseEntity<ReportDtos.MonthlyComparisonResponse> monthlyComparison(
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(reportService.monthlyComparison(currentUser));
    }

    /** GET /api/reports/export/csv */
    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal User currentUser
    ) {
        LocalDate[] range = resolveRange(startDate, endDate);
        String csv = reportService.exportCsv(range[0], range[1], currentUser);
        String filename = "duofinance_" + range[0] + "_" + range[1] + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(("\uFEFF" + csv).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * GET /api/reports/partner-comparison
     * RF39 — Comparativo entre parceiros: receitas, despesas e top categorias de cada um.
     * Parâmetros opcionais: startDate, endDate (padrão: mês atual)
     */
    @GetMapping("/partner-comparison")
    public ResponseEntity<ReportDtos.PartnerComparisonResponse> partnerComparison(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal User currentUser
    ) {
        LocalDate[] range = resolveRange(startDate, endDate);
        return ResponseEntity.ok(reportService.partnerComparison(range[0], range[1], currentUser));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private LocalDate[] resolveRange(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now();
        LocalDate start = startDate != null ? startDate : today.withDayOfMonth(1);
        LocalDate end   = endDate   != null ? endDate   : today.withDayOfMonth(today.lengthOfMonth());
        return new LocalDate[]{start, end};
    }
}