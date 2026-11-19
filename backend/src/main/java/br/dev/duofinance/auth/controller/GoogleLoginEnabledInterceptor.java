package br.dev.duofinance.auth.controller;

import br.dev.duofinance.auth.google.GoogleIdTokenVerifier;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gate for the Google sign-in routes. Runs before the request body is read, so with the feature off every request
 * gets 404 {@code GOOGLE_LOGIN_DISABLED}, even one whose body is malformed or fails validation.
 */
@Component
public class GoogleLoginEnabledInterceptor implements HandlerInterceptor {

    public static final String[] PATHS = {"/api/v1/auth/google", "/api/v1/auth/google/**"};

    private final GoogleIdTokenVerifier verifier;

    public GoogleLoginEnabledInterceptor(GoogleIdTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!verifier.enabled()) {
            throw new ApiException(ErrorCode.GOOGLE_LOGIN_DISABLED);
        }
        return true;
    }
}
