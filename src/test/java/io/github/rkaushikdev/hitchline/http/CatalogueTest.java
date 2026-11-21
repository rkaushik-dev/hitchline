package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.http.Catalogue.CatalogueEntry;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CatalogueTest {

    private static int statusOf(String code) {
        return Catalogue.entryFor(code).orElseThrow().status();
    }

    @Nested
    class TheCatalogue {

        @Test
        void coversEveryCodeTheApiAnswersWith() {
            assertThat(Catalogue.CATALOGUE.stream().map(CatalogueEntry::code))
                    .containsExactly(
                            "validation_failed", "not_found", "conflict", "forbidden", "unsupported", "internal_error");
        }

        @Test
        void carriesTheStatusEachOneMapsTo() {
            assertThat(statusOf("validation_failed")).isEqualTo(400);
            assertThat(statusOf("not_found")).isEqualTo(404);
            assertThat(statusOf("conflict")).isEqualTo(409);
            assertThat(statusOf("forbidden")).isEqualTo(403);
            assertThat(statusOf("unsupported")).isEqualTo(422);
            assertThat(statusOf("internal_error")).isEqualTo(500);
        }

        @Test
        void saysSomethingUsefulAboutEachOne() {
            for (CatalogueEntry entry : Catalogue.CATALOGUE) {
                assertThat(entry.meaning().length()).isGreaterThan(20);
                assertThat(entry.remedy().length()).isGreaterThan(20);
                assertThat(entry.title().length()).isGreaterThan(5);
            }
        }

        @Test
        void answersNothingForACodeItDoesNotKnow() {
            assertThat(Catalogue.entryFor("teapot")).isEmpty();
            assertThat(Catalogue.retryable("teapot")).isFalse();
        }

        @Test
        void saysWhichOnesAreWorthRetrying() {
            assertThat(Catalogue.retryable("conflict")).isTrue();
            assertThat(Catalogue.retryable("internal_error")).isTrue();
            assertThat(Catalogue.retryable("validation_failed")).isFalse();
        }

        @Test
        void findsTheCodesBehindAStatus() {
            assertThat(Catalogue.codesFor(404)).containsExactly("not_found");
            assertThat(Catalogue.codesFor(418)).isEmpty();
        }

        @Test
        void rendersATableForTheDocs() {
            String table = Catalogue.catalogueMarkdown();
            List<String> lines = Texts.split(table, "\n");
            assertThat(lines.get(0)).contains("| code |");
            assertThat(lines).hasSize(Catalogue.CATALOGUE.size() + 3);
            assertThat(table).contains("validation_failed");
        }
    }
}
