package io.github.rkaushikdev.hitchline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LoadedConfig;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.infra.SignalName;
import io.github.rkaushikdev.hitchline.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/** The api served over a real socket, the way a deployment runs it. */
@TestInstance(Lifecycle.PER_CLASS)
class ServerTest {

    private static final String CREW = "https://crew.example";
    private static final ApiToken ADMIN = new ApiToken("admin-token-0123456", "mem_FEDCBA9876543210", List.of("admin"));

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Runnable> signalled = new ArrayList<>();
    private final List<Integer> exits = new ArrayList<>();
    private Server.Running running;

    private static LoadedConfig loaded() {
        Config config = Config.read(Map.of())
                .withStore(StoreKind.MEMORY)
                .withPort(0)
                .withLogLevel(LogLevel.SILENT)
                .withTokens(List.of(ADMIN));
        return new LoadedConfig(config, List.of(CREW), Optional.empty());
    }

    @BeforeAll
    void start() {
        running = Server.serve(loaded(), (signal, listener) -> signalled.add(listener), exits::add);
    }

    @AfterAll
    void stop() {
        running.shutdown().run();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + path));
    }

    @Nested
    class OverASocket {

        @Test
        void answersTheHealthCheck() throws Exception {
            HttpResponse<String> response = send(at("/health"));
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = Json.parse(response.body());
            assertThat(body.get("status").asText()).isEqualTo("ok");
            assertThat(body.get("store").asText()).isEqualTo("memory");
            assertThat(response.headers().firstValue("x-request-id")).isPresent();
        }

        @Test
        void answersAnUnknownRouteWithAProblemBodyAndTheSecurityHeaders() throws Exception {
            HttpResponse<String> response = send(at("/nothing-here"));
            assertThat(response.statusCode()).isEqualTo(404);
            JsonNode body = Json.parse(response.body());
            assertThat(body.get("code").asText()).isEqualTo("not_found");
            assertThat(body.get("message").asText()).isEqualTo("no route for GET /nothing-here");
            assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
        }

        @Test
        void answersAWrongMethodOnAKnownPathAsAnUnknownRoute() throws Exception {
            HttpResponse<String> response = send(at("/health").DELETE());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(Json.parse(response.body()).get("message").asText()).isEqualTo("no route for DELETE /health");
        }

        @Test
        void takesAWriteWithAToken() throws Exception {
            HttpResponse<String> response = send(at("/crews")
                    .header("authorization", "Bearer " + ADMIN.token())
                    .header("content-type", "application/json")
                    .POST(BodyPublishers.ofString(
                            "{\"name\":\"Alpha production\",\"site\":\"Grane B\",\"siteZone\":\"Europe/Oslo\"}")));
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(Json.parse(response.body()).get("id").asText()).startsWith("crw_");
        }

        @Test
        void answersABrowserPreflightFromAnAllowedOrigin() throws Exception {
            HttpResponse<String> response = send(at("/crews")
                    .header("origin", CREW)
                    .header("access-control-request-method", "POST")
                    .method("OPTIONS", BodyPublishers.noBody()));
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.headers().firstValue("access-control-allow-methods"))
                    .hasValue("GET,POST,PUT,PATCH,DELETE");
            assertThat(response.headers().firstValue("access-control-allow-origin"))
                    .hasValue(CREW);
        }

        @Test
        void answersABodyThatIsNotJsonAsAFailureNobodyExpected() throws Exception {
            HttpResponse<String> response = send(at("/crews")
                    .header("authorization", "Bearer " + ADMIN.token())
                    .header("content-type", "application/json")
                    .POST(BodyPublishers.ofString("{not json")));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(Json.parse(response.body()).get("code").asText()).isEqualTo("internal_error");
        }
    }

    @Nested
    class StoppingOnASignal {

        @Test
        void wiresBothStopSignals() {
            assertThat(signalled).hasSize(SignalName.values().length);
        }
    }

    @Test
    void closesTheServerThenTheStoreAndExitsCleanlyOnASignal() {
        List<Runnable> listeners = new ArrayList<>();
        List<Integer> codes = new ArrayList<>();
        Server.Running other = Server.serve(loaded(), (signal, listener) -> listeners.add(listener), codes::add);
        assertThat(other.context().isActive()).isTrue();
        listeners.get(0).run();
        assertThat(other.context().isActive()).isFalse();
        assertThat(codes).containsExactly(0);
    }
}
