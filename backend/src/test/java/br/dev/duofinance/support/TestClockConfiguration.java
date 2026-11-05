package br.dev.duofinance.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Exposes a {@link MutableClock} as the primary {@link java.time.Clock} (it is a Clock) for tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
