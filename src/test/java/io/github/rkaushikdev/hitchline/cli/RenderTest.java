package io.github.rkaushikdev.hitchline.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RenderTest {

    private record Row(String date, String role, int count) {}

    private static final List<Row> ROWS =
            List.of(new Row("2025-01-06", "onshift", 1), new Row("2025-01-20", "offshift", 12));

    private static final List<Column<Row>> COLUMNS = List.of(
            Column.left("date", Row::date),
            Column.left("role", Row::role),
            Column.right("n", row -> Integer.toString(row.count())));

    private static List<String> linesOf(String text) {
        return Texts.split(text, "\n");
    }

    @Nested
    class Tables {

        @Test
        void linesTheColumnsUpUnderTheHeaders() {
            List<String> lines = linesOf(Render.renderTable(ROWS, COLUMNS));
            assertThat(lines.get(0)).isEqualTo("date        role       n");
            assertThat(lines.get(1)).isEqualTo("----------  --------  --");
            assertThat(lines.get(2)).isEqualTo("2025-01-06  onshift    1");
            assertThat(lines.get(3)).isEqualTo("2025-01-20  offshift  12");
        }

        @Test
        void widensAColumnToFitItsHeader() {
            List<Column<Row>> wide = List.of(Column.left("the day it fell on", Row::date));
            List<String> lines = linesOf(Render.renderTable(ROWS, wide));
            assertThat(lines.get(0)).isEqualTo("the day it fell on");
            assertThat(lines.get(2)).isEqualTo("2025-01-06");
        }

        @Test
        void rendersAHeaderAndARuleWithNoRows() {
            assertThat(linesOf(Render.renderTable(List.of(), COLUMNS))).hasSize(2);
        }

        @Test
        void refusesATableWithNoColumns() {
            assertThatThrownBy(() -> Render.renderTable(ROWS, List.of())).isInstanceOf(ValidationException.class);
        }

        @Test
        void doesNotLeaveTrailingSpacesOnALine() {
            for (String line : linesOf(Render.renderTable(ROWS, COLUMNS))) {
                assertThat(line).isEqualTo(Texts.trimEnd(line));
            }
        }
    }

    @Nested
    class BarsAndPercentages {

        @Test
        void fillsInProportion() {
            assertThat(Render.bar(0)).isEqualTo("..........");
            assertThat(Render.bar(0.5)).isEqualTo("#####.....");
            assertThat(Render.bar(1)).isEqualTo("##########");
        }

        @Test
        void clampsOutOfRangeValues() {
            assertThat(Render.bar(-1)).isEqualTo("..........");
            assertThat(Render.bar(2)).isEqualTo("##########");
        }

        @Test
        void takesAWidth() {
            assertThat(Render.bar(0.5, 4)).isEqualTo("##..");
        }

        @Test
        void rendersABlankBarForAValueThatIsNotANumber() {
            assertThat(Render.bar(Double.NaN)).isEqualTo("          ");
        }

        @Test
        void roundsAPercentageAndKeepsTheColumnWidth() {
            assertThat(Render.percent(0.5)).isEqualTo(" 50%");
            assertThat(Render.percent(1)).isEqualTo("100%");
            assertThat(Render.percent(0.004)).isEqualTo("  0%");
            assertThat(Render.percent(Double.NaN)).isEqualTo("   -");
        }
    }

    @Nested
    class HeadingsAndPairs {

        @Test
        void underlinesAHeadingToItsOwnWidth() {
            assertThat(Render.heading("Hitch")).isEqualTo("Hitch\n=====");
        }

        @Test
        void linesUpKeysAndValues() {
            assertThat(Render.keyValues(List.of(Map.entry("window", "2025-01-06..2025-01-19"), Map.entry("met", "12"))))
                    .isEqualTo("window  2025-01-06..2025-01-19\nmet     12");
        }

        @Test
        void pluralises() {
            assertThat(Render.pluralise(1, "check in")).isEqualTo("1 check in");
            assertThat(Render.pluralise(2, "check in")).isEqualTo("2 check ins");
            assertThat(Render.pluralise(0, "hitch", "hitches")).isEqualTo("0 hitches");
        }
    }
}
