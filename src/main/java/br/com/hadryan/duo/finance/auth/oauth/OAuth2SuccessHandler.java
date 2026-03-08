package br.com.hadryan.duo.finance.auth.oauth;

import br.com.hadryan.duo.finance.auth.RefreshTokenService;
import br.com.hadryan.duo.finance.auth.jwt.JwtService;
import br.com.hadryan.duo.finance.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Chamado pelo Spring Security após o OAuth2 login ser concluído com sucesso.
 * Emite o JWT próprio e redireciona o frontend com os tokens na query string.
 *
 * Em produção, prefira enviar os tokens via HttpOnly cookie ao invés de query param.
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final String frontendUrl;

    public OAuth2SuccessHandler(
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            @Value("${app.frontend-url}") String frontendUrl
    ) {
        this.jwtService          = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.frontendUrl         = frontendUrl;
    }

    @Override
    public void onAuthenticationSuccess(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        AuthenticatedOAuth2User oAuth2User = (AuthenticatedOAuth2User) authentication.getPrincipal();
        assert oAuth2User != null;
        User user = oAuth2User.getUser();

        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.create(user).getToken();

        // Redireciona para o frontend com os tokens
        // O frontend lê os params, armazena os tokens e limpa a URL
        String redirectUrl = UriComponentsBuilder
                .fromUriString(frontendUrl + "/auth/callback")
                .queryParam("access_token",  accessToken)
                .queryParam("refresh_token", refreshToken)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

}
