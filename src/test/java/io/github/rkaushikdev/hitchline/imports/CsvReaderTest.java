package io.github.rkaushikdev.hitchline.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.export.CsvExport;
import io.github.rkaushikdev.hitchline.export.CsvExport.CsvColumn;
import io.github.rkaushikdev.hitchline.imports.CsvReader.CsvTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Nested
    class ParsingRows {

        @Test
        void readsPlainCells() {
            assertThat(CsvReader.parse("a,b,c\n1,2,3\n"))
                    .containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
        }

        @Test
        void readsAFileWithNoTrailingNewline() {
            assertThat(CsvReader.parse("a,b\n1,2")).containsExactly(List.of("a", "b"), List.of("1", "2"));
        }

        @Test
        void readsCarriageReturnsAsLineEnds() {
            assertThat(CsvReader.parse("a,b\r\n1,2\r\n")).containsExactly(List.of("a", "b"), List.of("1", "2"));
        }

        @Test
        void readsAQuotedCellWithACommaInIt() {
            assertThat(CsvReader.parse("a,b\n\"one, two\",3\n").get(1)).containsExactly("one, two", "3");
        }

        @Test
        void readsAQuotedCellWithANewlineInIt() {
            assertThat(CsvReader.parse("a\n\"two\nlines\"\n").get(1)).containsExactly("two\nlines");
        }

        @Test
        void readsADoubledQuoteAsOneQuote() {
            assertThat(CsvReader.parse("a\n\"said \"\"no\"\"\"\n").get(1)).containsExactly("said \"no\"");
        }

        @Test
        void readsEmptyCells() {
            assertThat(CsvReader.parse("a,b,c\n1,,3\n").get(1)).containsExactly("1", "", "3");
            assertThat(CsvReader.parse("a,b\n,\n").get(1)).containsExactly("", "");
        }

        @Test
        void readsATrailingEmptyCell() {
            assertThat(CsvReader.parse("a,b\n1,\n").get(1)).containsExactly("1", "");
        }

        @Test
        void refusesAFileThatEndsInsideAQuote() {
            assertThatThrownBy(() -> CsvReader.parse("a\n\"never closed\n"))
                    .hasMessageContaining("ends inside a quoted value");
        }

        @Test
        void refusesAQuoteInTheMiddleOfAPlainValue() {
            assertThatThrownBy(() -> CsvReader.parse("a\nno\"pe\n")).hasMessageContaining("quote in the middle");
        }

        @Test
        void roundTripsWhatTheExporterWrites() {
            Function<String, String> note = row -> row;
            String written =
                    CsvExport.toCsv(List.of("one, two", "said \"no\""), List.of(new CsvColumn<>("note", note)));
            assertThat(CsvReader.parse(written))
                    .containsExactly(List.of("note"), List.of("one, two"), List.of("said \"no\""));
        }
    }

    @Nested
    class ReadingATable {

        private static final String TEXT =
                "Habit,At,Reading\nhab_1,2025-01-10T07:00:00Z,20\nhab_2,2025-01-11T07:00:00Z,\n";

        @Test
        void lowercasesTheHeaders() {
            assertThat(CsvReader.readTable(TEXT).headers()).containsExactly("habit", "at", "reading");
        }

        @Test
        void keysEachRowByHeader() {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("habit", "hab_1");
            expected.put("at", "2025-01-10T07:00:00Z");
            expected.put("reading", "20");
            Map<String, String> first = CsvReader.readTable(TEXT).rows().get(0);
            assertThat(first).isEqualTo(expected);
            assertThat(first.keySet()).containsExactly("habit", "at", "reading");
        }

        @Test
        void keepsAnEmptyCellAsAnEmptyString() {
            assertThat(CsvReader.readTable(TEXT).rows().get(1).get("reading")).isEmpty();
        }

        @Test
        void skipsABlankLineAtTheEnd() {
            assertThat(CsvReader.readTable(TEXT + "\n").rows()).hasSize(2);
        }

        @Test
        void refusesAFileWithNoHeader() {
            assertThatThrownBy(() -> CsvReader.readTable("")).hasMessageContaining("has no header line");
        }

        @Test
        void refusesARepeatedHeader() {
            assertThatThrownBy(() -> CsvReader.readTable("a,A\n1,2\n"))
                    .hasMessageContaining("names the column a twice");
        }

        @Test
        void refusesARowWithTheWrongNumberOfCells() {
            assertThatThrownBy(() -> CsvReader.readTable("a,b\n1,2,3\n"))
                    .hasMessageContaining("line 2 has 3 cells, not 2");
        }
    }

    @Nested
    class RequiredColumns {

        @Test
        void passesWhenTheyAreAllThere() {
            CsvTable table = CsvReader.readTable("habit,at\nhab_1,2025-01-10T07:00:00Z\n");
            assertThatCode(() -> table.requireColumns(List.of("habit", "at"))).doesNotThrowAnyException();
        }

        @Test
        void namesTheOnesThatAreMissing() {
            CsvTable table = CsvReader.readTable("habit\nhab_1\n");
            assertThatThrownBy(() -> table.requireColumns(List.of("habit", "at", "reading")))
                    .hasMessageContaining("missing the columns at, reading");
        }

        @Test
        void throwsADomainFailure() {
            CsvTable table = CsvReader.readTable("habit\nhab_1\n");
            assertThatThrownBy(() -> table.requireColumns(List.of("at"))).isInstanceOf(ValidationException.class);
        }
    }
}
