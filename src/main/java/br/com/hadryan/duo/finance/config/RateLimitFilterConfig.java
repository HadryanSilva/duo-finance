package br.com.hadryan.duo.finance.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registra o RateLimitFilter manualmente com precedência máxima.
 *
 * O filter NÃO tem @Component propositalmente — se tivesse, o Spring Boot
 * o registraria automaticamente para todas as URLs E o FilterRegistrationBean
 * o registraria novamente, causando duplo registro e falha no contexto.
 *
 * Ao instanciar aqui como @Bean, o Spring gerencia o ciclo de vida completo,
 * incluindo o @Scheduled definido no filter.
 */
@Configuration
public class RateLimitFilterConfig {

    @Bean
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter();
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitFilter rateLimitFilter
    ) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(rateLimitFilter);
        registration.addUrlPatterns(
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/forgot-password"
        );
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("rateLimitFilter");
        return registration;
    }
}