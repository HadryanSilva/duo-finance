package br.dev.duofinance.budget.controller;

import br.dev.duofinance.budget.dto.BudgetResponse;
import br.dev.duofinance.budget.dto.CreateBudgetRequest;
import br.dev.duofinance.budget.dto.UpdateBudgetRequest;
import br.dev.duofinance.budget.service.BudgetService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    /** Unique constraint on budgets.category_id (default PostgreSQL name). */
    private static final String CATEGORY_UNIQUE_CONSTRAINT = "budgets_category_id_key";

    private final BudgetService service;

    public BudgetController(BudgetService service) {
        this.service = service;
    }

    @GetMapping
    public List<BudgetResponse> list(
            CurrentUser current, @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.list(current.accountId(), month != null ? month : service.currentMonth());
    }

    @GetMapping("/{id}")
    public BudgetResponse get(
            CurrentUser current,
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.get(current.accountId(), id, month != null ? month : service.currentMonth());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse create(CurrentUser current, @Valid @RequestBody CreateBudgetRequest request) {
        try {
            return service.create(current.accountId(), request);
        } catch (DataIntegrityViolationException e) {
            // The service transaction has already rolled back here, so nothing is left half-flushed.
            if (violatesCategoryUnique(e)) {
                throw new ApiException(ErrorCode.BUDGET_EXISTS);
            }
            throw e;
        }
    }

    @PutMapping("/{id}")
    public BudgetResponse update(
            CurrentUser current, @PathVariable UUID id, @Valid @RequestBody UpdateBudgetRequest request) {
        return service.update(current.accountId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @PathVariable UUID id) {
        service.delete(current.accountId(), id);
    }

    private static boolean violatesCategoryUnique(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && CATEGORY_UNIQUE_CONSTRAINT.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
