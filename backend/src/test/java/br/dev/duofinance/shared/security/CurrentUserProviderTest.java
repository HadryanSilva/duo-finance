package br.dev.duofinance.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.duofinance.account.repository.AccountMemberRepository;
import br.dev.duofinance.user.model.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class CurrentUserProviderTest {

    private final AccountMemberRepository members = mock(AccountMemberRepository.class);
    private final CurrentUserProvider provider = new CurrentUserProvider(members);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticate(UUID userId, UUID accountId) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("acc", accountId.toString())
                .claim("role", "USER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void resolvesOncePerRequest() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(members.findAccountIdByUserId(userId)).thenReturn(Optional.of(accountId));
        authenticate(userId, accountId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        CurrentUser first = provider.get();
        CurrentUser second = provider.get();

        assertThat(first).isEqualTo(new CurrentUser(userId, accountId, UserRole.USER));
        assertThat(second).isSameAs(first);
        verify(members, times(1)).findAccountIdByUserId(userId);
    }

    @Test
    void withoutRequestContext_resolvesEveryTime() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(members.findAccountIdByUserId(userId)).thenReturn(Optional.of(accountId));
        authenticate(userId, accountId);

        provider.get();
        provider.get();

        verify(members, times(2)).findAccountIdByUserId(userId);
    }
}
