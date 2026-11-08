package br.dev.duofinance.account.controller;

import br.dev.duofinance.account.dto.AcceptInvitationRequest;
import br.dev.duofinance.account.dto.CreateInvitationRequest;
import br.dev.duofinance.account.dto.InvitationPreviewResponse;
import br.dev.duofinance.account.dto.InvitationSummary;
import br.dev.duofinance.account.service.InvitationService;
import br.dev.duofinance.auth.controller.RefreshCookies;
import br.dev.duofinance.auth.dto.AuthResponse;
import br.dev.duofinance.auth.service.AuthSessionService.SessionTokens;
import br.dev.duofinance.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Only {@code GET /{token}} is public (see SecurityConfig); everything else requires a session. */
@RestController
@RequestMapping("/api/v1/account/invitations")
public class InvitationController {

    private final InvitationService invitations;
    private final RefreshCookies cookies;

    public InvitationController(InvitationService invitations, RefreshCookies cookies) {
        this.invitations = invitations;
        this.cookies = cookies;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationSummary create(CurrentUser current, @Valid @RequestBody CreateInvitationRequest request) {
        return invitations.create(current, request.email());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(CurrentUser current, @PathVariable UUID id) {
        invitations.cancel(current, id);
    }

    @GetMapping("/{token}")
    public InvitationPreviewResponse preview(@PathVariable String token) {
        return invitations.preview(token);
    }

    /** The body is optional; a missing body means {@code confirmDiscard = false}. */
    @PostMapping("/{token}/accept")
    public AuthResponse accept(
            CurrentUser current,
            @PathVariable String token,
            @RequestBody(required = false) AcceptInvitationRequest request,
            HttpServletResponse response) {
        boolean confirmDiscard = request != null && request.confirmDiscard();
        SessionTokens session = invitations.accept(current, token, confirmDiscard);
        cookies.set(response, session.rawRefreshToken());
        return AuthResponse.of(session);
    }
}
