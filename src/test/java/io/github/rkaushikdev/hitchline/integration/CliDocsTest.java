package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.http.Api;
import io.github.rkaushikdev.hitchline.http.Manifest;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteDoc;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDocsTest {

    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);

    @TempDir
    Path directory;

    private Recorder context;

    @BeforeEach
    void create() {
        context = Context.recordingContext(
                MemoryStore.create(Clocks.fixedClock("2025-07-21T09:00:00Z"), IdSources.randomIds()), CONFIG);
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    @Nested
    class TheApiReference {

        @Test
        void opensWithTheVersionAndListsEveryRoute() {
            assertThat(run("docs", "api")).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).startsWith("# hitchline api " + Api.VERSION);
            for (RouteDoc route : Manifest.ROUTES) {
                assertThat(text).contains("### " + route.method() + " " + route.path());
            }
        }

        @Test
        void saysWhatScopeEachRouteNeeds() {
            run("docs", "api");
            assertThat(context.text()).contains("needs the admin scope");
            assertThat(context.text()).contains("no token needed");
        }

        @Test
        void describesTheParametersARouteTakes() {
            run("docs", "api");
            assertThat(context.text()).contains("| window | query | yes |");
        }

        @Test
        void endsWithTheErrorCodes() {
            run("docs", "api");
            assertThat(context.text()).contains("## Errors");
            assertThat(context.text()).contains("validation_failed");
        }

        @Test
        void takesABaseUrl() {
            run("docs", "api", "--base", "https://hitchline.example");
            assertThat(context.text()).contains("https://hitchline.example");
        }

        @Test
        void writesItToAFileWhenAsked() throws IOException {
            Path file = directory.resolve("api.md");
            run("docs", "api", "--out", file.toString());
            assertThat(context.text()).contains("wrote the reference");
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("# hitchline api");
        }
    }

    @Nested
    class TheShorterForms {

        @Test
        void listsTheRoutesOnTheirOwn() {
            run("docs", "routes");
            assertThat(Texts.split(context.text(), "\n")).hasSize(Manifest.ROUTES.size());
        }

        @Test
        void printsTheErrorTableOnItsOwn() {
            run("docs", "errors");
            assertThat(Texts.split(context.text(), "\n").get(0)).contains("| code |");
        }

        @Test
        void writesAnOpenapiDocument() throws IOException {
            Path file = directory.resolve("openapi.json");
            run("docs", "openapi", "--out", file.toString());
            String parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    .get("openapi")
                    .textValue();
            assertThat(parsed).isEqualTo("3.1.0");
        }

        @Test
        void printsTheOpenapiDocumentWhenNoFileIsNamed() {
            run("docs", "openapi");
            assertThat(context.text()).startsWith("{");
        }
    }
}
