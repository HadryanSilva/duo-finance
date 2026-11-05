package br.dev.duofinance.shared.security;

import br.dev.duofinance.auth.service.TokenService;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.shared.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Stateless JWT resource server. CSRF is off: there are no session cookies, and the refresh cookie is SameSite=Strict. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/api/actuator/health",
        "/api/v1/public/**",
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/verify-email",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password"
    };

    private static final String[] API_DOCS_PATHS = {"/v3/api-docs/**", "/swagger-ui/**"};

    /** Throwaway controllers used by integration tests; open only under the {@code test} profile. */
    private static final String TEST_PATHS = "/api/v1/__test/**";

    private final JacksonJsonHttpMessageConverter problemConverter = new JacksonJsonHttpMessageConverter();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        boolean test = environment.acceptsProfiles(Profiles.of("test"));
        AuthenticationEntryPoint entryPoint =
                (request, response, ex) -> writeProblem(response, ErrorCode.UNAUTHENTICATED, "Authentication required");
        AccessDeniedHandler accessDeniedHandler =
                (request, response, ex) -> writeProblem(response, ErrorCode.FORBIDDEN, "Access denied");

        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PATHS).permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/account/invitations/*")
                            .permitAll();
                    if (!prod) {
                        auth.requestMatchers(API_DOCS_PATHS).permitAll();
                    }
                    if (test) {
                        auth.requestMatchers(TEST_PATHS).permitAll();
                    }
                    auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private void writeProblem(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        problemConverter.write(
                GlobalExceptionHandler.problem(code, detail),
                MediaType.APPLICATION_PROBLEM_JSON,
                new ServletServerHttpResponse(response));
    }
}
