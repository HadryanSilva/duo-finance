package br.dev.duofinance.auth.controller;

import br.dev.duofinance.auth.dto.AuthResponse;
import br.dev.duofinance.auth.dto.ForgotPasswordRequest;
import br.dev.duofinance.auth.dto.LoginRequest;
import br.dev.duofinance.auth.dto.RegisterRequest;
import br.dev.duofinance.auth.dto.ResetPasswordRequest;
import br.dev.duofinance.auth.dto.TokenRequest;
import br.dev.duofinance.auth.service.AuthSessionService;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.auth.service.EmailVerificationService;
import br.dev.duofinance.auth.service.LoginService;
import br.dev.duofinance.auth.service.PasswordResetService;
import br.dev.duofinance.auth.service.RegistrationService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.security.CurrentUser;
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
    private final LoginService login;
    private final EmailVerificationService verification;
    private final PasswordResetService passwordReset;

    public AuthController(
            AuthSessionService sessions,
            RefreshCookies cookies,
            RegistrationService registration,
            LoginService login,
            EmailVerificationService verification,
            PasswordResetService passwordReset) {
        this.verification = verification;
        this.passwordReset = passwordReset;
        this.sessions = sessions;
        this.cookies = cookies;
        this.registration = registration;
        this.login = login;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        SessionTokens session = registration.register(request);
        cookies.set(response, session.rawRefreshToken());
        return AuthResponse.of(session);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        SessionTokens session = login.login(request.email(), request.password());
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

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest request) {
        verification.verify(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(CurrentUser current) {
        verification.resend(current.userId());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordReset.requestReset(request.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.token(), request.newPassword());
    }
}
