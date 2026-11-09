package br.dev.duofinance.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.DuoFinanceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

class ProfileGuardTest {

    private final ProfileGuard guard = new ProfileGuard();

    private static MockEnvironment profiles(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void prodAndE2eTogether_isRejected() {
        assertThatThrownBy(() -> guard.postProcessEnvironment(profiles("prod", "e2e"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("profile e2e must never run with prod");
    }

    @Test
    void prodAlone_andDevWithE2e_areAccepted() {
        assertThatCode(() -> guard.postProcessEnvironment(profiles("prod"), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.postProcessEnvironment(profiles("dev", "e2e"), null))
                .doesNotThrowAnyException();
    }

    @Test
    void prodAndE2eTogether_failsAtStartup_viaSpringProfilesActive() {
        // spring.profiles.active is the property SPRING_PROFILES_ACTIVE maps to.
        assertThatThrownBy(() -> new SpringApplicationBuilder(DuoFinanceApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties("spring.profiles.active=prod,e2e")
                        .run())
                .hasStackTraceContaining("profile e2e must never run with prod");
    }
}
