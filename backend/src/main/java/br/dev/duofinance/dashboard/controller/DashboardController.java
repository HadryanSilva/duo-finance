package br.dev.duofinance.dashboard.controller;

import br.dev.duofinance.dashboard.dto.DashboardResponse;
import br.dev.duofinance.dashboard.service.DashboardService;
import br.dev.duofinance.shared.security.CurrentUser;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardResponse dashboard(
            CurrentUser current, @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.dashboard(current.accountId(), month != null ? month : service.currentMonth());
    }
}
