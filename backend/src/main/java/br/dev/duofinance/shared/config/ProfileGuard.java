package br.dev.duofinance.shared.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start when {@code e2e} is active together with {@code prod}: the e2e profile opens an outbox endpoint
 * and relaxes registration, which must never be reachable in production. Registered in {@code META-INF/spring.factories}.
 * Runs after config data is loaded, so profiles from {@code SPRING_PROFILES_ACTIVE}, {@code spring.profiles.active}
 * and {@code spring.profiles.include} are all visible.
 */
public class ProfileGuard implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("prod") && environment.matchesProfiles("e2e")) {
            throw new IllegalStateException("profile e2e must never run with prod");
        }
    }
}
