package br.dev.duofinance.bankaccount.controller;

import br.dev.duofinance.bankaccount.dto.BankAccountRequest;
import br.dev.duofinance.bankaccount.dto.BankAccountResponse;
import br.dev.duofinance.bankaccount.service.BankAccountService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bank-accounts")
public class BankAccountController {

    /** Foreign keys from transactions and recurring_rules to bank_accounts (default PostgreSQL names). */
    private static final Pattern IN_USE_CONSTRAINT =
            Pattern.compile("(transactions|recurring_rules)_(destination_)?bank_account_id_fkey");

    private final BankAccountService service;

    public BankAccountController(BankAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<BankAccountResponse> list(
            CurrentUser current, @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(current.accountId(), includeArchived);
    }

    @GetMapping("/{id}")
    public BankAccountResponse get(CurrentUser current, @PathVariable UUID id) {
        return service.get(current.accountId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BankAccountResponse create(CurrentUser current, @Valid @RequestBody BankAccountRequest request) {
        return service.create(current.accountId(), request);
    }

    @PutMapping("/{id}")
    public BankAccountResponse update(
            CurrentUser current, @PathVariable UUID id, @Valid @RequestBody BankAccountRequest request) {
        return service.update(current.accountId(), id, request);
    }

    @PostMapping("/{id}/archive")
    public BankAccountResponse archive(CurrentUser current, @PathVariable UUID id) {
        return service.archive(current.accountId(), id);
    }

    @PostMapping("/{id}/unarchive")
    public BankAccountResponse unarchive(CurrentUser current, @PathVariable UUID id) {
        return service.unarchive(current.accountId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @PathVariable UUID id) {
        try {
            service.delete(current.accountId(), id);
        } catch (DataIntegrityViolationException e) {
            // The service transaction has already rolled back here, so nothing is left half-flushed.
            if (isInUseViolation(e)) {
                throw new ApiException(ErrorCode.BANK_ACCOUNT_IN_USE);
            }
            throw e;
        }
    }

    private static boolean isInUseViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && IN_USE_CONSTRAINT.matcher(cve.getConstraintName()).matches()) {
                return true;
            }
        }
        return false;
    }
}
