package br.dev.duofinance.access.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.access.service.AccessCodeService.GeneratedAccessCode;
import br.dev.duofinance.shared.error.ApiException;
import br.dev.duofinance.shared.error.ErrorCode;
import br.dev.duofinance.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AccessCodeServiceTest {

    @Autowired
    AccessCodeService service;

    @Autowired
    MutableClock clock;

    private GeneratedAccessCode gen() {
        return service.generate(null, 1, null, "test", null);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCESS_CODE_INVALID));
    }

    @Test
    void generatedCode_hasDisplayFormat() {
        assertThat(gen().plainCode()).matches("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
    }

    @Test
    void consume_acceptsLowercaseWithoutHyphens() {
        GeneratedAccessCode code = gen();

        assertThatCode(() -> service.consume(code.plainCode().toLowerCase().replace("-", ""), "ana@mail.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void consume_rejectsExhaustedExpiredRevokedOrOtherEmail() {
        GeneratedAccessCode exhausted = gen();
        service.consume(exhausted.plainCode(), "a@mail.com");
        assertInvalid(() -> service.consume(exhausted.plainCode(), "b@mail.com"));

        GeneratedAccessCode expired =
                service.generate(null, 1, Instant.now(clock).plus(Duration.ofDays(1)), "exp", null);
        clock.advance(Duration.ofDays(2));
        assertInvalid(() -> service.consume(expired.plainCode(), "a@mail.com"));

        GeneratedAccessCode revoked = gen();
        service.revoke(revoked.id());
        assertInvalid(() -> service.consume(revoked.plainCode(), "a@mail.com"));

        GeneratedAccessCode bound = service.generate("Ana@Mail.com ", 1, null, "bound", null);
        assertInvalid(() -> service.consume(bound.plainCode(), "other@mail.com"));
        assertThatCode(() -> service.consume(bound.plainCode(), "ana@mail.com")).doesNotThrowAnyException();
    }

    @Test
    void consume_unknownCode_isRejected() {
        assertInvalid(() -> service.consume("0000-0000-0000", "a@mail.com"));
    }

    @Test
    void consume_concurrentSingleUse_onlyOneSucceeds() throws Exception {
        GeneratedAccessCode code = gen();
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.consume(code.plainCode(), "a@mail.com");
                    return true;
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo(ErrorCode.ACCESS_CODE_INVALID);
                    return false;
                }
            }));
        }
        ready.await();
        go.countDown();
        int successes = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(1);
    }
}
