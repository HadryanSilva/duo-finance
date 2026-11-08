package br.dev.duofinance.transaction.controller;

import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.transaction.dto.TransactionRequest;
import br.dev.duofinance.transaction.dto.TransactionResponse;
import br.dev.duofinance.transaction.service.TransactionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final String RULE_DATE_CONSTRAINT = "uq_transactions_rule_date";

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(CurrentUser current, @Valid @RequestBody TransactionRequest request) {
        return service.create(current.accountId(), current.userId(), request);
    }

    @GetMapping("/{id}")
    public TransactionResponse get(CurrentUser current, @PathVariable UUID id) {
        return service.get(current.accountId(), id);
    }

    @PutMapping("/{id}")
    public TransactionResponse update(
            CurrentUser current, @PathVariable UUID id, @Valid @RequestBody TransactionRequest request) {
        try {
            return service.update(current.accountId(), id, request);
        } catch (DataIntegrityViolationException e) {
            // The service transaction has already rolled back here.
            if (violates(e, RULE_DATE_CONSTRAINT)) {
                throw new ApiException(ErrorCode.RECURRING_DATE_TAKEN);
            }
            throw e;
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @PathVariable UUID id) {
        service.delete(current.accountId(), id);
    }

    private static boolean violates(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && constraint.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
