package io.github.rkaushikdev.hitchline.http.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.HttpServer;
import io.github.rkaushikdev.hitchline.http.middleware.RateLimit.Limiter;
import io.github.rkaushikdev.hitchline.http.middleware.RateLimit.TooManyRequestsException;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.MutableClock;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class RateLimitTest {

    /** A route to read and write. */
    @RestController
    static class ThingsRoute {

        @PostMapping("/things")
        public Map<String, Boolean> write() {
            return Map.of("ok", true);
        }

        @GetMapping("/things")
        public Map<String, Boolean> read() {
            return Map.of("ok", true);
        }
    }

    @Nested
    class TheBucket {

        @Test
        void letsABurstThroughUpToTheCapacity() {
            Limiter limiter = RateLimit.createLimiter(3, 60);
            for (int index = 0; index < 3; index += 1) {
                assertThatCode(() -> limiter.take("ada", 0)).doesNotThrowAnyException();
            }
            assertThatThrownBy(() -> limiter.take("ada", 0)).isInstanceOf(TooManyRequestsException.class);
        }

        @Test
        void refillsOverTime() {
            Limiter limiter = RateLimit.createLimiter(2, 60);
            limiter.take("ada", 0);
            limiter.take("ada", 0);
            assertThatThrownBy(() -> limiter.take("ada", 0)).isInstanceOf(TooManyRequestsException.class);
            assertThatCode(() -> limiter.take("ada", 1_000)).doesNotThrowAnyException();
        }

        @Test
        void doesNotRefillPastTheCapacity() {
            Limiter limiter = RateLimit.createLimiter(2, 60);
            limiter.take("ada", 0);
            assertThat(limiter.peek("ada")).isEqualTo(1);
            limiter.take("ada", 600_000);
            assertThat(limiter.peek("ada")).isEqualTo(1);
        }

        @Test
        void keepsCallersApart() {
            Limiter limiter = RateLimit.createLimiter(1, 60);
            limiter.take("ada", 0);
            assertThatCode(() -> limiter.take("bo", 0)).doesNotThrowAnyException();
            assertThat(limiter.size()).isEqualTo(2);
        }

        @Test
        void saysHowLongToWait() {
            Limiter limiter = RateLimit.createLimiter(1, 60);
            limiter.take("ada", 0);
            TooManyRequestsException refused =
                    catchThrowableOfType(TooManyRequestsException.class, () -> limiter.take("ada", 0));
            assertThat(refused).isNotNull();
            assertThat(refused.retryAfterSeconds()).isGreaterThan(0);
        }

        @Test
        void refusesANonsenseConfiguration() {
            assertThatThrownBy(() -> RateLimit.createLimiter(0, 60)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> RateLimit.createLimiter(5, 0)).hasMessageContaining("above zero");
        }
    }

    @Nested
    class OverHttp {

        private static final Map<String, String> ADA = Map.of("authorization", "Bearer ada-token-0123456789");
        private static final Map<String, String> BO = Map.of("authorization", "Bearer bo-token-01234567890");

        private MutableClock clock;
        private Inject app;

        @BeforeEach
        void build() {
            clock = Clocks.fixedClock("2025-04-11T09:00:00Z");
            HttpApp built = HttpServer.build(
                    Config.read(Map.of()).withStore(StoreKind.MEMORY).withLogLevel(LogLevel.SILENT),
                    MemoryStore.create(clock, IdSources.randomIds()));
            RateLimit.register(built, clock, 2, 60);
            built.addRoutes(new ThingsRoute());
            app = Inject.into(built);
        }

        private Reply post(Map<String, String> headers) {
            return app.send("POST", "/things", headers, null);
        }

        @Test
        void letsReadsThroughHoweverManyThereAre() {
            for (int index = 0; index < 5; index += 1) {
                assertThat(app.get("/things").status()).isEqualTo(200);
            }
        }

        @Test
        void turnsABurstOfWritesAwayWith429() {
            assertThat(post(ADA).status()).isEqualTo(200);
            assertThat(post(ADA).status()).isEqualTo(200);
            Reply third = post(ADA);
            assertThat(third.status()).isEqualTo(429);
            assertThat(third.header("retry-after")).isPresent();
        }

        @Test
        void letsTheCallerBackInOnceTheBucketRefills() {
            post(ADA);
            post(ADA);
            clock.advance(2_000);
            assertThat(post(ADA).status()).isEqualTo(200);
        }

        @Test
        void keepsTwoTokensApart() {
            post(ADA);
            post(ADA);
            assertThat(post(BO).status()).isEqualTo(200);
        }

        @Test
        void answersAProblemBodyRatherThanHtml() {
            post(ADA);
            post(ADA);
            Reply refused = post(ADA);
            assertThat(refused.json().get("code").asText()).isEqualTo("conflict");
        }
    }
}
