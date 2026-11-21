package io.github.rkaushikdev.hitchline.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.RosterService.RosterDay;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.RoleSource;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.export.CsvExport.CsvColumn;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CsvExportTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private record Named(String name) {}

    @Nested
    class Quoting {

        @Test
        void leavesAPlainValueAlone() {
            assertThat(CsvExport.csvCell("onshift")).isEqualTo("onshift");
        }

        @Test
        void quotesAValueWithAComma() {
            assertThat(CsvExport.csvCell("Vasquez, Ada")).isEqualTo("\"Vasquez, Ada\"");
        }

        @Test
        void doublesAQuoteInsideAQuotedValue() {
            assertThat(CsvExport.csvCell("said \"no\"")).isEqualTo("\"said \"\"no\"\"\"");
        }

        @Test
        void quotesAValueWithANewline() {
            assertThat(CsvExport.csvCell("two\nlines")).isEqualTo("\"two\nlines\"");
        }

        @Test
        void joinsCellsWithCommas() {
            assertThat(CsvExport.csvRow(List.of("a", "b,c", "d"))).isEqualTo("a,\"b,c\",d");
        }
    }

    @Nested
    class BuildingATable {

        private final List<Named> rows = List.of(new Named("Ada"), new Named("Bo"));
        private final List<CsvColumn<Named>> columns = List.of(new CsvColumn<>("name", Named::name));

        @Test
        void writesAHeaderAndOneLinePerRowEndingEachWithCrlf() {
            assertThat(CsvExport.toCsv(rows, columns)).isEqualTo("name\r\nAda\r\nBo\r\n");
        }

        @Test
        void writesJustTheHeaderForNoRows() {
            assertThat(CsvExport.toCsv(List.of(), columns)).isEqualTo("name\r\n");
        }

        @Test
        void refusesATableWithNoColumns() {
            assertThatThrownBy(() -> CsvExport.toCsv(rows, List.of())).isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class CheckInRows {

        private final CheckIn entry = CheckIn.builder(
                        Ids.checkInId("chk_0123456789ABCDEF"),
                        TREADMILL,
                        ADA,
                        Zones.parseInstant("2025-01-10T07:30:00Z"),
                        Zones.parseInstant("2025-01-10T07:31:00Z"),
                        CheckInSource.WATCH)
                .reading(22)
                .note("before the shift, early")
                .build();

        private String csvOf(CheckIn value) {
            List<ResolvedCheckIn> resolved = ResolvedCheckIn.resolveAll(Fixtures.aMember(), List.of(value));
            return CsvExport.checkInsToCsv(resolved);
        }

        @Test
        void namesEveryColumn() {
            assertThat(CsvExport.CHECK_IN_COLUMNS.stream().map(CsvColumn::header))
                    .containsExactly(
                            "id",
                            "habit_id",
                            "member_id",
                            "at",
                            "rotation_day",
                            "role",
                            "zone",
                            "reading",
                            "source",
                            "note");
        }

        @Test
        void writesTheRotationDayBesideTheInstant() {
            String line = Texts.split(csvOf(entry), "\r\n").get(1);
            assertThat(line)
                    .contains("2025-01-10T07:30:00.000Z")
                    .contains("2025-01-10")
                    .contains("onshift")
                    .contains("Europe/Oslo");
        }

        @Test
        void quotesANoteWithACommaInIt() {
            assertThat(csvOf(entry)).contains("\"before the shift, early\"");
        }

        @Test
        void leavesAnAbsentReadingEmptyRatherThanWritingAPlaceholder() {
            String csv = csvOf(entry.withReading(OptionalDouble.empty()));
            assertThat(csv).doesNotContain("undefined").doesNotContain("null").doesNotContain("Optional");
            assertThat(Texts.split(csv, "\r\n").get(1)).contains(",,watch,");
        }
    }

    @Nested
    class ResultRows {

        @Test
        void writesTheWindowAndTheCounts() {
            ObligationResult result = new ObligationResult(
                    new Obligation(TREADMILL, ADA, ObligationScope.HITCH, DateRange.parse("2025-01-06..2025-02-02"), 3),
                    2,
                    OptionalDouble.of(40),
                    AdherenceStatus.PARTIAL,
                    0.67);
            String line =
                    Texts.split(CsvExport.resultsToCsv(List.of(result)), "\r\n").get(1);
            assertThat(line).isEqualTo("hab_0123456789ABCDEF,hitch,2025-01-06..2025-02-02,2025-02-02,3,2,40,partial");
        }
    }

    @Nested
    class RosterRows {

        @Test
        void writesALineForEachDay() {
            String csv = CsvExport.rosterToCsv(List.of(
                    new RosterDay(CivilDate.parse("2025-01-06"), DayRole.ONSHIFT, RoleSource.PATTERN, "Europe/Oslo")));
            assertThat(csv).isEqualTo("date,role,source,zone\r\n2025-01-06,onshift,pattern,Europe/Oslo\r\n");
        }
    }
}
