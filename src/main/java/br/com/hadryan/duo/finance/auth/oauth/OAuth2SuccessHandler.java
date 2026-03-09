package br.com.hadryan.duo.finance.auth.oauth;

import br.com.hadryan.duo.finance.auth.RefreshTokenService;
import br.com.hadryan.duo.finance.auth.jwt.JwtService;
import br.com.hadryan.duo.finance.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

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
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {

        AuthenticatedOAuth2User oAuth2User = (AuthenticatedOAuth2User) authentication.getPrincipal();
        User user = oAuth2User.getUser();

        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.create(user).getToken();

        // Invalida sessão OAuth2 — não é mais necessária
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        String redirectUrl = UriComponentsBuilder
                .fromUriString(frontendUrl + "/auth/callback")
                .queryParam("access_token",  accessToken)
                .queryParam("refresh_token", refreshToken)
                .build()
                .toUriString();

        // Seta o header Location explicitamente e usa 302
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", redirectUrl);
        response.getWriter().flush();
    }
}