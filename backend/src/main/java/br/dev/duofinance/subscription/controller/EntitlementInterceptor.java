package br.dev.duofinance.subscription.controller;

import br.dev.duofinance.shared.security.CurrentUserProvider;
import br.dev.duofinance.subscription.service.EntitlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gate for the financial routes. Unauthenticated requests pass through so security answers 401; for authenticated
 * ones the account comes from {@link CurrentUserProvider}, so a stale token fails with SESSION_STALE first.
 */
@Component
public class EntitlementInterceptor implements HandlerInterceptor {

    private final CurrentUserProvider currentUser;
    private final EntitlementService entitlement;

    public EntitlementInterceptor(CurrentUserProvider currentUser, EntitlementService entitlement) {
        this.currentUser = currentUser;
        this.entitlement = entitlement;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!currentUser.isAuthenticated()) {
            return true;
        }
        entitlement.requireAccess(currentUser.get().accountId());
        return true;
    }
}
