package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.ImportService.ImportOptions;
import io.github.rkaushikdev.hitchline.app.ImportService.ImportReport;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ImportServiceTest {

    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String HEADER = "habit_id,at,reading,note,idempotency_key";

    private MemoryStore store;
    private ImportService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
        service = new ImportService(store);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06")));
    }

    private static String file(String... rows) {
        return HEADER + "\n" + String.join("\n", rows);
    }

    private List<CheckIn> stored() {
        return store.checkIns().byHabit(TREADMILL);
    }

    @Nested
    class ACleanFile {

        @Test
        void storesEveryRow() {
            ImportReport report = service.checkIns(
                    file(TREADMILL + ",2025-01-10T07:00:00Z,20,,", TREADMILL + ",2025-01-11T07:00:00Z,25,,"));
            assertThat(report).isEqualTo(new ImportReport(2, 2, 0, List.of()));
            assertThat(stored()).hasSize(2);
        }

        @Test
        void keepsTheReadingsAndTheNotes() {
            service.checkIns(file(TREADMILL + ",2025-01-10T07:00:00Z,20,\"felt slow, cold\","));
            CheckIn first = stored().get(0);
            assertThat(first.reading()).hasValue(20.0);
            assertThat(first.note()).isEqualTo("felt slow, cold");
        }

        @Test
        void marksTheRowsAsAnImport() {
            service.checkIns(file(TREADMILL + ",2025-01-10T07:00:00Z,,,"));
            assertThat(stored().get(0).source()).isEqualTo(CheckInSource.IMPORT);
        }

        @Test
        void takesASource() {
            service.checkIns(
                    file(TREADMILL + ",2025-01-10T07:00:00Z,,,"), new ImportOptions(false, CheckInSource.SUPERVISOR));
            assertThat(stored().get(0).source()).isEqualTo(CheckInSource.SUPERVISOR);
        }
    }

    @Nested
    class RowsThatWillNotGoIn {

        @Test
        void reportsABadRowAndKeepsTheGoodOnes() {
            ImportReport report = service.checkIns(file(
                    TREADMILL + ",2025-01-10T07:00:00Z,20,,",
                    TREADMILL + ",not a time,20,,",
                    TREADMILL + ",2025-01-12T07:00:00Z,20,,"));
            assertThat(report.accepted()).isEqualTo(2);
            assertThat(report.failures()).hasSize(1);
            assertThat(report.failures().get(0).line()).isEqualTo(3);
        }

        @Test
        void reportsAHabitItHasNeverHeardOf() {
            ImportReport report = service.checkIns(file("hab_9999999999999999,2025-01-10T07:00:00Z,,,"));
            assertThat(report.failures().get(0).message()).contains("does not exist");
        }

        @Test
        void reportsAReadingThatIsNotANumber() {
            ImportReport report = service.checkIns(file(TREADMILL + ",2025-01-10T07:00:00Z,quite a lot,,"));
            assertThat(report.failures().get(0).message()).contains("is not a number");
        }

        @Test
        void reportsAnIdThatIsNotAnIdAtAll() {
            assertThat(service.checkIns(file("nonsense,2025-01-10T07:00:00Z,,,"))
                            .failures())
                    .hasSize(1);
        }
    }

    @Nested
    class Repeats {

        @Test
        void countsARepeatedIdempotencyKeyRatherThanStoringItTwice() {
            ImportReport report = service.checkIns(file(
                    TREADMILL + ",2025-01-10T07:00:00Z,20,,paper-0110",
                    TREADMILL + ",2025-01-10T07:00:00Z,20,,paper-0110"));
            assertThat(report.accepted()).isEqualTo(1);
            assertThat(report.duplicates()).isEqualTo(1);
            assertThat(stored()).hasSize(1);
        }

        @Test
        void isSafeToRunTheSameFileTwice() {
            String text = file(TREADMILL + ",2025-01-10T07:00:00Z,20,,paper-0110");
            service.checkIns(text);
            ImportReport second = service.checkIns(text);
            assertThat(second.duplicates()).isEqualTo(1);
            assertThat(stored()).hasSize(1);
        }
    }

    @Nested
    class ADryRun {

        @Test
        void checksTheRowsWithoutStoringAnything() {
            ImportReport report = service.checkIns(
                    file(TREADMILL + ",2025-01-10T07:00:00Z,20,,"), new ImportOptions(true, CheckInSource.IMPORT));
            assertThat(report.accepted()).isEqualTo(1);
            assertThat(stored()).isEmpty();
        }

        @Test
        void stillReportsAHabitItCannotFind() {
            ImportReport report = service.checkIns(
                    file("hab_9999999999999999,2025-01-10T07:00:00Z,,,"),
                    new ImportOptions(true, CheckInSource.IMPORT));
            assertThat(report.failures()).hasSize(1);
        }
    }

    @Nested
    class TheFileItself {

        @Test
        void refusesOneMissingARequiredColumn() {
            assertThatThrownBy(() -> service.checkIns("habit_id\nhab_1\n"))
                    .hasMessageContaining("missing the columns at");
        }

        @Test
        void refusesOneWithNoHeader() {
            assertThatThrownBy(() -> service.checkIns("")).hasMessageContaining("has no header line");
        }

        @Test
        void readsAFileWithOnlyAHeaderAsNothingToDo() {
            assertThat(service.checkIns(HEADER + "\n")).isEqualTo(new ImportReport(0, 0, 0, List.of()));
        }
    }

    @Nested
    class DescribingARun {

        @Test
        void readsBackInOneLine() {
            assertThat(new ImportReport(3, 2, 1, List.of()).describe())
                    .isEqualTo("3 read, 2 accepted, 1 already there, 0 refused");
        }
    }
}
