package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.Manifest.Method;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteDoc;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteParameter;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteScope;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ManifestTest {

    private static List<String> keysOf(JsonNode tree) {
        List<String> keys = new ArrayList<>();
        tree.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Nested
    class TheManifest {

        @Test
        void namesASummaryAndAScopeForEveryRoute() {
            for (RouteDoc route : Manifest.ROUTES) {
                assertThat(route.summary().length()).isGreaterThan(5);
                assertThat(route.scope()).isIn((Object[]) RouteScope.values());
            }
        }

        @Test
        void leavesHealthAndMetricsOpen() {
            assertThat(Manifest.routesByScope(RouteScope.NONE).stream().map(RouteDoc::path))
                    .containsExactly("/health", "/metrics");
        }

        @Test
        void findsARouteByMethodAndPath() {
            assertThat(Manifest.findRoute(Method.POST, "/habits").map(RouteDoc::scope))
                    .hasValue(RouteScope.WRITE);
            assertThat(Manifest.findRoute(Method.GET, "/habits").map(RouteDoc::scope))
                    .hasValue(RouteScope.READ);
            assertThat(Manifest.findRoute(Method.DELETE, "/habits")).isEmpty();
        }

        @Test
        void marksAWindowAsRequiredWhereItIs() {
            RouteDoc roster =
                    Manifest.findRoute(Method.GET, "/members/:memberId/roster").orElseThrow();
            assertThat(windowOf(roster).required()).isTrue();
            RouteDoc checkIns = Manifest.findRoute(Method.GET, "/members/:memberId/check-ins")
                    .orElseThrow();
            assertThat(windowOf(checkIns).required()).isFalse();
        }

        private RouteParameter windowOf(RouteDoc route) {
            return route.parameters().stream()
                    .filter(parameter -> parameter.name().equals("window"))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        void describesPagingOnTheListThatPages() {
            RouteDoc habits = Manifest.findRoute(Method.GET, "/habits").orElseThrow();
            assertThat(habits.parameters().stream().map(RouteParameter::name)).contains("after");
        }
    }

    @Nested
    class TheOpenapiDocument {

        private final ObjectNode document = Manifest.openApiDocument("0.1.0");

        @Test
        void declaresTheVersionOfTheSpecAndOfTheApi() {
            assertThat(document.get("openapi").asText()).isEqualTo("3.1.0");
            assertThat(document.get("info").get("title").asText()).isEqualTo("hitchline");
            assertThat(document.get("info").get("version").asText()).isEqualTo("0.1.0");
        }

        @Test
        void rewritesPathParametersIntoBraces() {
            List<String> paths = keysOf(document.get("paths"));
            assertThat(paths).contains("/members/{memberId}/roster");
            assertThat(paths).doesNotContain("/members/:memberId/roster");
        }

        @Test
        void putsTwoMethodsOnOnePathTogether() {
            assertThat(keysOf(document.get("paths").get("/habits"))).containsExactly("post", "get");
        }

        @Test
        void declaresBearerSecurityOnEverythingButTheOpenRoutes() {
            JsonNode paths = document.get("paths");
            assertThat(paths.get("/health").get("get").get("security")).isEqualTo(Json.array());
            assertThat(Json.stringify(paths.get("/habits").get("post").get("security")))
                    .isEqualTo("[{\"bearerAuth\":[]}]");
        }

        @Test
        void takesATitle() {
            ObjectNode named = Manifest.openApiDocument("1.0.0", "hitchline api");
            assertThat(named.get("info").get("title").asText()).isEqualTo("hitchline api");
        }

        @Test
        void refusesAVersionThatIsNotOne() {
            assertThatThrownBy(() -> Manifest.openApiDocument("one")).isInstanceOf(ValidationException.class);
        }
    }
}
