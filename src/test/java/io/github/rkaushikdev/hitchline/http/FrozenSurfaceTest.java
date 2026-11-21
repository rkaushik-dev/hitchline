package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.http.Manifest.Location;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteDoc;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteParameter;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteScope;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The routes the api promises. This list is the contract: adding to it is a feature, changing or
 * removing a line is a breaking change, and either way somebody has to edit this file on purpose
 * rather than by accident.
 */
class FrozenSurfaceTest {

    private static final List<String> FROZEN = List.of(
            "GET /health",
            "GET /metrics",
            "POST /habits",
            "GET /habits",
            "GET /habits/:habitId",
            "PATCH /habits/:habitId",
            "POST /check-ins",
            "GET /members/:memberId/check-ins",
            "POST /check-ins/:checkInId/void",
            "PATCH /check-ins/:checkInId",
            "GET /members/:memberId/roster",
            "GET /members/:memberId/adherence",
            "GET /members/:memberId/nudges",
            "POST /members/:memberId/sync",
            "GET /audit",
            "POST /crews",
            "GET /crews/:crewId/comparison");

    private static String registered() {
        return Api.build(
                        HttpServerTest.CONFIG,
                        MemoryStore.create(Clocks.fixedClock("2025-08-25T09:00:00Z"), IdSources.randomIds()))
                .printRoutes();
    }

    private static List<String> keys() {
        return Manifest.ROUTES.stream()
                .map(route -> route.method() + " " + route.path())
                .toList();
    }

    @Nested
    class TheFrozenSurface {

        @Test
        void isExactlyWhatTheManifestDescribes() {
            assertThat(keys()).isEqualTo(FROZEN);
        }

        @Test
        void describesOnlyRoutesTheServerActuallyRegisters() {
            String printed = registered();
            assertThat(printed).isNotBlank();
            for (RouteDoc route : Manifest.ROUTES) {
                if (route.path().equals("/metrics")) {
                    continue;
                }
                String first = Texts.split(route.path(), "/").stream()
                        .filter(part -> !part.isEmpty())
                        .findFirst()
                        .orElse("");
                assertThat(printed).contains(first);
            }
        }

        @Test
        void givesEveryRouteAScopeSomebodyChose() {
            for (RouteDoc route : Manifest.ROUTES) {
                assertThat(route.scope()).isIn((Object[]) RouteScope.values());
            }
        }

        @Test
        void leavesOnlyHealthAndMetricsOpen() {
            assertThat(Manifest.ROUTES.stream()
                            .filter(route -> route.scope() == RouteScope.NONE)
                            .map(RouteDoc::path))
                    .containsExactly("/health", "/metrics");
        }

        @Test
        void neverHasTwoEntriesForOneMethodAndPath() {
            assertThat(new HashSet<>(keys())).hasSameSizeAs(keys());
        }

        @Test
        void namesAPathParameterForEveryOneInThePath() {
            for (RouteDoc route : Manifest.ROUTES) {
                List<String> inPath = Manifest.pathParameters(route.path());
                route.parameters().stream()
                        .filter(parameter -> parameter.in() == Location.PATH)
                        .map(RouteParameter::name)
                        .forEach(name -> assertThat(inPath).contains(name));
            }
        }
    }
}
