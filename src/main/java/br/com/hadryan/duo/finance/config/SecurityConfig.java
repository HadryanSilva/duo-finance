package br.com.hadryan.duo.finance.config;

import br.com.hadryan.duo.finance.auth.jwt.JwtAuthFilter;
import br.com.hadryan.duo.finance.auth.oauth.DuoOAuth2UserService;
import br.com.hadryan.duo.finance.auth.oauth.OAuth2SuccessHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.savedrequest.NullRequestCache;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final DuoOAuth2UserService oAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final String frontendUrl;

    public SecurityConfig(
            JwtAuthFilter jwtAuthFilter,
            DuoOAuth2UserService oAuth2UserService,
            OAuth2SuccessHandler oAuth2SuccessHandler,
            @Value("${app.frontend-url}") String frontendUrl
    ) {
        this.jwtAuthFilter      = jwtAuthFilter;
        this.oAuth2UserService  = oAuth2UserService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.frontendUrl        = frontendUrl;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // IF_REQUIRED: cria sessão apenas quando o OAuth2 precisar (fluxo de login).
                // As rotas de API são stateless via JWT — a sessão não é usada nelas.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/**",
                                "/login/**",
                                "/auth/refresh",
                                "/auth/logout",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .anyRequest().authenticated()
                )

                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo ->
                                userInfo.oidcUserService(oAuth2UserService))
                        .successHandler(oAuth2SuccessHandler)
                )

                // JWT filter roda antes — se o token for válido, autentica sem precisar de sessão
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}