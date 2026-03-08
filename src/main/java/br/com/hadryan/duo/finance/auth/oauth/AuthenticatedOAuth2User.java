package br.com.hadryan.duo.finance.auth.oauth;

import br.com.hadryan.duo.finance.user.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Adapta o {@link User} interno à interface {@link OAuth2User} exigida
 * pelo Spring Security, mantendo acesso aos atributos originais do provider.
 */
@Getter
@Setter
@RequiredArgsConstructor
public class AuthenticatedOAuth2User implements OidcUser {

    private final OidcUser delegate;
    private final User user;

    // ── OidcUser ──────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    // ── OAuth2User ────────────────────────────────────────────────────────────

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getName() {
        return user.getEmail();
    }

}
