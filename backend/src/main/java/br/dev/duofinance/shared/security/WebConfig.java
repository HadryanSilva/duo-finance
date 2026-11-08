package br.dev.duofinance.shared.security;

import br.dev.duofinance.subscription.controller.EntitlementInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private static final String[] FINANCIAL_PATHS = {
        "/api/v1/bank-accounts/**",
        "/api/v1/categories/**",
        "/api/v1/transactions/**",
        "/api/v1/recurring-rules/**",
        "/api/v1/budgets/**",
        "/api/v1/dashboard/**"
    };

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final EntitlementInterceptor entitlementInterceptor;

    public WebConfig(
            CurrentUserArgumentResolver currentUserArgumentResolver, EntitlementInterceptor entitlementInterceptor) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.entitlementInterceptor = entitlementInterceptor;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(entitlementInterceptor).addPathPatterns(FINANCIAL_PATHS);
    }
}
