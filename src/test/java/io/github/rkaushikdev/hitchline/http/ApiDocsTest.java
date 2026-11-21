package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.http.Catalogue.CatalogueEntry;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteDoc;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The committed reference has to match what the generator writes. A route added without
 * regenerating it is the usual way documentation starts lying, and this is the cheapest place to
 * catch it.
 */
class ApiDocsTest {

    private static final Path PATH = Path.of("docs", "api.md");

    private static String committed() throws IOException {
        return Files.readString(PATH, StandardCharsets.UTF_8);
    }

    @Nested
    class TheCommittedReference {

        @Test
        void isExactlyWhatTheGeneratorWritesToday() throws IOException {
            assertThat(committed()).isEqualTo(ApiDocs.apiMarkdown(Api.VERSION));
        }

        @Test
        void namesEveryRouteTheManifestHolds() throws IOException {
            String text = committed();
            for (RouteDoc route : Manifest.ROUTES) {
                assertThat(text).contains("### " + route.method() + " " + route.path());
            }
        }

        @Test
        void namesEveryErrorCode() throws IOException {
            String text = committed();
            for (CatalogueEntry entry : Catalogue.CATALOGUE) {
                assertThat(text).contains(entry.code());
            }
        }

        @Test
        void carriesTheVersionTheBuildIsAt() throws IOException {
            assertThat(committed()).startsWith("# hitchline api " + Api.VERSION);
        }
    }
}
