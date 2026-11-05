package br.dev.duofinance.auth.controller;

import br.dev.duofinance.auth.dto.AuthResponse;
import br.dev.duofinance.auth.dto.RegisterRequest;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.auth.service.RegistrationService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthSessionService sessions;
    private final RefreshCookies cookies;
    private final RegistrationService registration;

    public AuthController(AuthSessionService sessions, RefreshCookies cookies, RegistrationService registration) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.registration = registration;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        SessionTokens session = registration.register(request);
        cookies.set(response, session.rawRefreshToken());
        return AuthResponse.of(session);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookies.read(request).orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));
        SessionTokens session = sessions.refresh(raw);
        cookies.set(response, session.rawRefreshToken());
        return AuthResponse.of(session);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        cookies.read(request).ifPresent(sessions::revokeFamilyOf);
        cookies.clear(response);
    }
}
