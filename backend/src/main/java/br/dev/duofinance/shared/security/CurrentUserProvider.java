package br.dev.duofinance.shared.security;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.account.repository.MemberSession;
import br.dev.duofinance.auth.service.TokenService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.user.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves {@link CurrentUser} from the JWT, treating the database as the authority for the account: a token whose
 * {@code acc} no longer matches the user's membership (or whose user is gone), or that was issued before the user's
 * sessions were revoked ({@code users.sessions_valid_after}), is rejected with {@code SESSION_STALE}.
 */
@Component
public class CurrentUserProvider {

    private static final String CACHE_ATTRIBUTE = CurrentUserProvider.class.getName();

    private final AccountMemberRepository members;

    public CurrentUserProvider(AccountMemberRepository members) {
        this.members = members;
    }

    /** True when the request carries a JWT-authenticated principal. */
    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Jwt;
    }

    /** Resolved once per request: later calls in the same request reuse the cached {@link CurrentUser}. */
    public CurrentUser get() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            if (request.getAttribute(CACHE_ATTRIBUTE) instanceof CurrentUser cached) {
                return cached;
            }
            CurrentUser resolved = resolve();
            request.setAttribute(CACHE_ATTRIBUTE, resolved);
            return resolved;
        }
        return resolve();
    }

    private CurrentUser resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        UUID userId;
        UUID claimedAccountId;
        UserRole role;
        try {
            userId = UUID.fromString(jwt.getSubject());
            claimedAccountId = UUID.fromString(jwt.getClaimAsString(TokenService.ACCOUNT_CLAIM));
            role = UserRole.valueOf(jwt.getClaimAsString(TokenService.ROLE_CLAIM));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.SESSION_STALE, "Session is no longer valid");
        }
        MemberSession session = members.findSessionByUserId(userId)
                .filter(s -> s.accountId().equals(claimedAccountId))
                .filter(s -> issuedWithinEpoch(jwt.getIssuedAt(), s.sessionsValidAfter()))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE, "Session is no longer valid"));
        return new CurrentUser(userId, session.accountId(), role);
    }

    /**
     * A token issued (whole seconds, as {@code iat} carries) at or after the epoch is valid; the epoch is stored
     * truncated to seconds so the session started right after a revocation stays valid.
     */
    private static boolean issuedWithinEpoch(@Nullable Instant issuedAt, @Nullable Instant sessionsValidAfter) {
        if (sessionsValidAfter == null) {
            return true;
        }
        return issuedAt != null && !issuedAt.isBefore(sessionsValidAfter);
    }
}
