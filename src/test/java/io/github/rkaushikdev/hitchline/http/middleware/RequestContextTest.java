package io.github.rkaushikdev.hitchline.http.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.HttpServer;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import io.github.rkaushikdev.hitchline.util.Texts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RequestContextTest {

    /** A route that answers with its own request id. */
    @RestController
    static class ThingRoute {

        @GetMapping("/thing")
        public Map<String, String> thing(HttpServletRequest request) {
            return Map.of("id", RequestContext.idOf(request).orElse(""));
        }
    }

    /** A route that fails. */
    @RestController
    static class BoomRoute {

        @GetMapping("/boom")
        public Map<String, String> boom() {
            throw new IllegalStateException("no");
        }
    }

    private static MockHttpServletRequest startedAt(double ms) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestContext.STARTED_AT_MS, ms);
        return request;
    }

    @Nested
    class ReadingASuppliedId {

        @Test
        void keepsOneThatLooksLikeAnId() {
            assertThat(RequestContext.readRequestId("req-0123456789")).hasValue("req-0123456789");
        }

        @Test
        void dropsOneThatDoesNot() {
            assertThat(RequestContext.readRequestId("short")).isEmpty();
            assertThat(RequestContext.readRequestId("has spaces in it")).isEmpty();
            assertThat(RequestContext.readRequestId("x".repeat(65))).isEmpty();
            assertThat(RequestContext.readRequestId((String) null)).isEmpty();
            assertThat(RequestContext.readRequestId(List.of("a", "b"))).isEmpty();
        }
    }

    @Nested
    class OverHttp {

        private int minted;
        private HttpApp built;

        @BeforeEach
        void build() {
            minted = 0;
            built = HttpServer.build(
                    Config.read(Map.of()).withStore(StoreKind.MEMORY).withLogLevel(LogLevel.SILENT),
                    MemoryStore.create(Clocks.fixedClock("2025-04-14T09:00:00Z"), IdSources.randomIds()));
            RequestContext.register(
                    built,
                    () -> {
                        minted += 1;
                        return "req-" + Texts.padStart(String.valueOf(minted), 8, '0');
                    },
                    () -> 1_000);
            built.addRoutes(new ThingRoute());
        }

        @Test
        void mintsAnIdAndAnswersWithIt() {
            Reply response = Inject.into(built).get("/thing");
            assertThat(response.header(RequestContext.REQUEST_ID_HEADER)).hasValue("req-00000001");
            assertThat(response.json().get("id").asText()).isEqualTo("req-00000001");
        }

        @Test
        void mintsANewOneForEachRequest() {
            Inject app = Inject.into(built);
            app.get("/thing");
            Reply second = app.get("/thing");
            assertThat(second.header(RequestContext.REQUEST_ID_HEADER)).hasValue("req-00000002");
        }

        @Test
        void keepsAnIdTheCallerSent() {
            Reply response =
                    Inject.into(built).get("/thing", Map.of(RequestContext.REQUEST_ID_HEADER, "from-the-gateway-01"));
            assertThat(response.header(RequestContext.REQUEST_ID_HEADER)).hasValue("from-the-gateway-01");
            assertThat(minted).isZero();
        }

        @Test
        void replacesOneThatDoesNotLookLikeAnId() {
            Reply response = Inject.into(built).get("/thing", Map.of(RequestContext.REQUEST_ID_HEADER, "nope"));
            assertThat(response.header(RequestContext.REQUEST_ID_HEADER)).hasValue("req-00000001");
        }

        @Test
        void answersWithTheIdEvenWhenTheRouteFails() {
            built.addRoutes(new BoomRoute());
            Reply response = Inject.into(built).get("/boom");
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.header(RequestContext.REQUEST_ID_HEADER)).isPresent();
        }
    }

    @Nested
    class ElapsedTime {

        @Test
        void measuresFromTheStartOfTheRequest() {
            assertThat(RequestContext.elapsedMs(startedAt(100), 350)).isEqualTo(250);
        }

        @Test
        void readsZeroWhenNothingWasRecorded() {
            assertThat(RequestContext.elapsedMs(new MockHttpServletRequest(), 350))
                    .isZero();
        }

        @Test
        void neverGoesNegativeOnAClockThatJumped() {
            assertThat(RequestContext.elapsedMs(startedAt(500), 100)).isZero();
        }
    }
}
