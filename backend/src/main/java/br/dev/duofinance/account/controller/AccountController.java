package br.dev.duofinance.account.controller;

import br.dev.duofinance.account.dto.AccountResponse;
import br.dev.duofinance.account.dto.UpdateAccountRequest;
import br.dev.duofinance.account.service.AccountService;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public AccountResponse get(CurrentUser current) {
        return accounts.get(current);
    }

    @PatchMapping
    public AccountResponse rename(CurrentUser current, @Valid @RequestBody UpdateAccountRequest request) {
        return accounts.rename(current, request.name());
    }
}
