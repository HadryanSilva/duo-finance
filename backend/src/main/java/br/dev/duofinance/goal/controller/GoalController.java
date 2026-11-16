package br.dev.duofinance.goal.controller;

import br.dev.duofinance.goal.dto.GoalSettingsResponse;
import br.dev.duofinance.goal.dto.UpdateGoalSettingsRequest;
import br.dev.duofinance.goal.service.GoalSettingsService;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    private final GoalSettingsService settings;

    public GoalController(GoalSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/settings")
    public GoalSettingsResponse settings(CurrentUser current) {
        return settings.get(current.accountId());
    }

    @PutMapping("/settings")
    public GoalSettingsResponse updateSettings(
            CurrentUser current, @Valid @RequestBody UpdateGoalSettingsRequest request) {
        return settings.update(current.accountId(), request);
    }
}
