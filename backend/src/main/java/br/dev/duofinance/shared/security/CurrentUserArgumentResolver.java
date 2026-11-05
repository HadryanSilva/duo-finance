package br.dev.duofinance.shared.security;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.auth.service.TokenService;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.user.model.UserRole;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} from the JWT, treating the database as the authority for the account: a token whose
 * {@code acc} no longer matches the user's membership (or whose user is gone) is rejected with {@code SESSION_STALE}.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final AccountMemberRepository members;

    public CurrentUserArgumentResolver(AccountMemberRepository members) {
        this.members = members;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public CurrentUser resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
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
        UUID accountId = members.findAccountIdByUserId(userId)
                .filter(claimedAccountId::equals)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_STALE, "Session is no longer valid"));
        return new CurrentUser(userId, accountId, role);
    }
}
