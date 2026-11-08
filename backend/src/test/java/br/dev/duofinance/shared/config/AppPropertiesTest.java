package br.dev.duofinance.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void missingPublicBaseUrl_failsStartup() {
        runner.run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("publicBaseUrl"));
    }

    @Test
    void publicBaseUrlSet_binds() {
        runner.withPropertyValues("app.public-base-url=https://duo.example")
                .run(context -> assertThat(context.getBean(AppProperties.class).publicBaseUrl())
                        .hasToString("https://duo.example"));
    }
}
