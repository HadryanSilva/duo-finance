package br.dev.duofinance.access.controller;

import br.dev.duofinance.access.dto.WaitlistEntryResponse;
import br.dev.duofinance.access.dto.WaitlistPageResponse;
import br.dev.duofinance.access.model.WaitlistStatus;
import br.dev.duofinance.access.service.WaitlistService;
import br.dev.duofinance.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/waitlist")
public class AdminWaitlistController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WaitlistService waitlist;

    public AdminWaitlistController(WaitlistService waitlist) {
        this.waitlist = waitlist;
    }

    @GetMapping
    public WaitlistPageResponse list(
            CurrentUser current,
            @RequestParam(required = false) WaitlistStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return waitlist.list(status, Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @PostMapping("/{id}/approve")
    public WaitlistEntryResponse approve(CurrentUser current, @PathVariable UUID id) {
        return waitlist.approve(id, current.userId());
    }

    @PostMapping("/{id}/reject")
    public WaitlistEntryResponse reject(CurrentUser current, @PathVariable UUID id) {
        return waitlist.reject(id);
    }
}
