package io.github.rkaushikdev.hitchline.http.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.HttpServer;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class HardeningTest {

    private static final String CREW = "https://crew.example";

    /** One ordinary route. */
    @RestController
    static class ThingRoute {

        @GetMapping("/thing")
        public Map<String, Boolean> thing() {
            return Map.of("ok", true);
        }
    }

    private Inject app;

    @BeforeEach
    void build() {
        HttpApp built = HttpServer.build(
                Config.read(Map.of()).withStore(StoreKind.MEMORY).withLogLevel(LogLevel.SILENT),
                MemoryStore.create(Clocks.fixedClock("2025-06-18T09:00:00Z"), IdSources.randomIds()));
        Hardening.register(built, List.of(CREW));
        built.addRoutes(new ThingRoute());
        app = Inject.into(built);
    }

    @Nested
    class TheBodyLimit {

        @Test
        void hasASensibleDefault() {
            assertThat(Hardening.DEFAULT_MAX_BODY_BYTES).isEqualTo(65_536);
        }

        @Test
        void refusesALimitThatIsTooSmallOrTooLarge() {
            assertThatThrownBy(() -> Hardening.checkBodyLimit(10)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> Hardening.checkBodyLimit(64 * 1024 * 1024))
                    .hasMessageContaining("kilobyte and eight megabytes");
            assertThatCode(() -> Hardening.checkBodyLimit(1_024)).doesNotThrowAnyException();
        }
    }

    @Nested
    class Origins {

        @Test
        void allowsOneOnTheList() {
            assertThat(Hardening.isAllowedOrigin(CREW, List.of(CREW))).isTrue();
        }

        @Test
        void turnsAwayAnythingElse() {
            assertThat(Hardening.isAllowedOrigin("https://elsewhere.example", List.of(CREW)))
                    .isFalse();
            assertThat(Hardening.isAllowedOrigin(null, List.of(CREW))).isFalse();
            assertThat(Hardening.isAllowedOrigin(CREW, List.of())).isFalse();
        }
    }

    @Nested
    class TheHeaders {

        @Test
        void areSentOnEveryResponse() {
            Reply response = app.get("/thing");
            for (String name : Hardening.SECURITY_HEADERS.keySet()) {
                assertThat(response.header(name)).isPresent();
            }
            assertThat(response.header("x-content-type-options")).hasValue("nosniff");
        }

        @Test
        void areSentOnAnErrorToo() {
            Reply response = app.get("/nothing-here");
            assertThat(response.status()).isEqualTo(404);
            assertThat(response.header("x-frame-options")).hasValue("DENY");
        }

        @Test
        void tellABrowserNotToCacheAReading() {
            assertThat(app.get("/thing").header("cache-control")).hasValue("no-store");
        }
    }

    @Nested
    class CrossOriginRequests {

        @Test
        void answersAPreflightFromAnAllowedOrigin() {
            Reply response = app.send("OPTIONS", "/thing", Map.of("origin", CREW), null);
            assertThat(response.status()).isEqualTo(204);
            assertThat(response.header("access-control-allow-methods"))
                    .hasValueSatisfying(methods -> assertThat(methods).contains("POST"));
        }

        @Test
        void turnsAwayAPreflightFromAnywhereElse() {
            Reply response = app.send("OPTIONS", "/thing", Map.of("origin", "https://elsewhere.example"), null);
            assertThat(response.status()).isEqualTo(404);
        }

        @Test
        void echoesAnAllowedOriginOnAnOrdinaryResponse() {
            Reply response = app.get("/thing", Map.of("origin", CREW));
            assertThat(response.header("access-control-allow-origin")).hasValue(CREW);
            assertThat(response.header("vary")).hasValue("origin");
        }

        @Test
        void saysNothingAboutAnOriginItDoesNotKnow() {
            Reply response = app.get("/thing", Map.of("origin", "https://elsewhere.example"));
            assertThat(response.header("access-control-allow-origin")).isEmpty();
        }
    }
}
