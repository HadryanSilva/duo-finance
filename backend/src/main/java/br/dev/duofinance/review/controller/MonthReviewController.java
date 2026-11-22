package br.dev.duofinance.review.controller;

import br.dev.duofinance.review.dto.MonthReviewResponse;
import br.dev.duofinance.review.service.MonthReviewService;
import br.dev.duofinance.shared.security.CurrentUser;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/month-review")
public class MonthReviewController {

    private final MonthReviewService service;

    public MonthReviewController(MonthReviewService service) {
        this.service = service;
    }

    /** Without {@code month}, the last month that is over everywhere. */
    @GetMapping
    public MonthReviewResponse review(
            CurrentUser current, @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.review(current.accountId(), month != null ? month : service.lastClosedMonth());
    }
}
