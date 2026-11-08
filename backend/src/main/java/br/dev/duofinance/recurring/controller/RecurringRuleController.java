package br.dev.duofinance.recurring.controller;

import br.dev.duofinance.recurring.dto.CreateRecurringRuleRequest;
import br.dev.duofinance.recurring.dto.RecurringRuleResponse;
import br.dev.duofinance.recurring.dto.UpdateRecurringRuleRequest;
import br.dev.duofinance.recurring.service.RecurringRuleService;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recurring-rules")
public class RecurringRuleController {

    private final RecurringRuleService service;

    public RecurringRuleController(RecurringRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecurringRuleResponse> list(CurrentUser current) {
        return service.list(current.accountId());
    }

    @GetMapping("/{id}")
    public RecurringRuleResponse get(CurrentUser current, @PathVariable UUID id) {
        return service.get(current.accountId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringRuleResponse create(CurrentUser current, @Valid @RequestBody CreateRecurringRuleRequest request) {
        return service.create(current.accountId(), current.userId(), request);
    }

    @PutMapping("/{id}")
    public RecurringRuleResponse update(
            CurrentUser current, @PathVariable UUID id, @Valid @RequestBody UpdateRecurringRuleRequest request) {
        return service.update(current.accountId(), id, request);
    }

    @PostMapping("/{id}/activate")
    public RecurringRuleResponse activate(CurrentUser current, @PathVariable UUID id) {
        return service.activate(current.accountId(), id);
    }

    @PostMapping("/{id}/deactivate")
    public RecurringRuleResponse deactivate(CurrentUser current, @PathVariable UUID id) {
        return service.deactivate(current.accountId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @PathVariable UUID id) {
        service.delete(current.accountId(), id);
    }
}
