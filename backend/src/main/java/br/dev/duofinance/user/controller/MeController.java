package br.dev.duofinance.user.controller;

import br.dev.duofinance.auth.controller.RefreshCookies;
import br.dev.duofinance.auth.dto.AuthResponse;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.security.CurrentUser;
import br.dev.duofinance.user.dto.ChangePasswordRequest;
import br.dev.duofinance.user.dto.DeleteMeRequest;
import br.dev.duofinance.user.dto.MeResponse;
import br.dev.duofinance.user.dto.UpdateMeRequest;
import br.dev.duofinance.user.service.MeService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeService me;
    private final RefreshCookies cookies;

    public MeController(MeService me, RefreshCookies cookies) {
        this.me = me;
        this.cookies = cookies;
    }

    @GetMapping
    public MeResponse get(CurrentUser current) {
        return me.get(current);
    }

    @PatchMapping
    public MeResponse update(CurrentUser current, @Valid @RequestBody UpdateMeRequest request) {
        return me.update(current, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(CurrentUser current, @Valid @RequestBody DeleteMeRequest request, HttpServletResponse response) {
        me.delete(current, request.password());
        cookies.clear(response);
    }

    @PutMapping("/password")
    public AuthResponse changePassword(
            CurrentUser current, @Valid @RequestBody ChangePasswordRequest request, HttpServletResponse response) {
        SessionTokens session = me.changePassword(current, request.currentPassword(), request.newPassword());
        cookies.set(response, session.rawRefreshToken());
        return AuthResponse.of(session);
    }
}
