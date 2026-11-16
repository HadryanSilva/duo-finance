package br.dev.duofinance.goal.controller;

import br.dev.duofinance.goal.dto.CreateGoalRequest;
import br.dev.duofinance.goal.dto.GoalResponse;
import br.dev.duofinance.goal.dto.GoalSettingsResponse;
import br.dev.duofinance.goal.dto.GoalsOverview;
import br.dev.duofinance.goal.dto.UpdateGoalRequest;
import br.dev.duofinance.goal.dto.UpdateGoalSettingsRequest;
import br.dev.duofinance.goal.service.GoalService;
import br.dev.duofinance.goal.service.GoalSettingsService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    /** Partial unique index on goals (account_id, lower(name)) where not completed. */
    private static final String ACTIVE_NAME_UNIQUE_INDEX = "uq_goals_active_name";

    private final GoalSettingsService settings;
    private final GoalService service;

    public GoalController(GoalSettingsService settings, GoalService service) {
        this.settings = settings;
        this.service = service;
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

    @GetMapping
    public GoalsOverview overview(
            CurrentUser current, @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.overview(current.accountId(), month != null ? month : service.currentMonth());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalResponse create(CurrentUser current, @Valid @RequestBody CreateGoalRequest request) {
        return mapNameConflict(() -> service.create(current.accountId(), request));
    }

    @PutMapping("/{id}")
    public GoalResponse update(
            CurrentUser current, @PathVariable UUID id, @Valid @RequestBody UpdateGoalRequest request) {
        return mapNameConflict(() -> service.update(current.accountId(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @PathVariable UUID id) {
        service.delete(current.accountId(), id);
    }

    @PostMapping("/{id}/complete")
    public GoalResponse complete(CurrentUser current, @PathVariable UUID id) {
        return service.complete(current.accountId(), id);
    }

    @PostMapping("/{id}/reopen")
    public GoalResponse reopen(CurrentUser current, @PathVariable UUID id) {
        return mapNameConflict(() -> service.reopen(current.accountId(), id));
    }

    private static <T> T mapNameConflict(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataIntegrityViolationException e) {
            // The service transaction has already rolled back here, so nothing is left half-flushed.
            if (violatesActiveName(e)) {
                throw new ApiException(ErrorCode.GOAL_NAME_TAKEN);
            }
            throw e;
        }
    }

    private static boolean violatesActiveName(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && ACTIVE_NAME_UNIQUE_INDEX.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
