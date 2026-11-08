package br.dev.duofinance.account.controller;

import br.dev.duofinance.account.dto.AccountResponse;
import br.dev.duofinance.account.dto.UpdateAccountRequest;
import br.dev.duofinance.account.service.AccountService;
import br.dev.duofinance.account.service.MembershipService;
import br.dev.duofinance.auth.controller.RefreshCookies;
import br.dev.duofinance.auth.dto.AuthResponse;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountService accounts;
    private final MembershipService membership;
    private final RefreshCookies cookies;

    public AccountController(AccountService accounts, MembershipService membership, RefreshCookies cookies) {
        this.accounts = accounts;
        this.membership = membership;
        this.cookies = cookies;
    }

    @PostMapping("/leave")
    public AuthResponse leave(CurrentUser current, HttpServletResponse response) {
        SessionTokens session = membership.leave(current);
        cookies.set(response, session.rawRefreshToken());
        return AuthResponse.of(session);
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
