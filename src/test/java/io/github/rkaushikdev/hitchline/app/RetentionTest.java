package io.github.rkaushikdev.hitchline.app;

import static io.github.rkaushikdev.hitchline.app.Retention.DEFAULT_RETENTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.Retention.RetentionPlan;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Signatures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RetentionTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final CivilDate TODAY = CivilDate.parse("2025-06-16");
    private static final Retention POLICY =
            DEFAULT_RETENTION.withKeepMonths(24).withKeepNoteMonths(12).withFloorMonths(6);

    private int next;

    private CheckIn at(String iso) {
        return at(iso, "");
    }

    private CheckIn at(String iso, String note) {
        next += 1;
        Instant when = Zones.parseInstant(iso);
        return CheckIn.builder(
                        Ids.checkInId("chk_" + Texts.padStart(Integer.toString(next), 16, '0')),
                        TREADMILL,
                        ADA,
                        when,
                        when,
                        CheckInSource.APP)
                .note(note)
                .build();
    }

    private static RetentionPlan plan(List<CheckIn> entries) {
        return POLICY.plan(entries, TODAY, "UTC");
    }

    @Nested
    class ThePolicy {

        @Test
        void hasADefaultThatKeepsFiveYears() {
            assertThat(DEFAULT_RETENTION.keepMonths()).isEqualTo(60);
            assertThat(new Retention(60, 24, 12)).isEqualTo(DEFAULT_RETENTION);
        }

        @Test
        void takesAnOverrideForOneField() {
            assertThat(DEFAULT_RETENTION.withKeepMonths(24).keepMonths()).isEqualTo(24);
        }

        @Test
        void refusesAPeriodThatIsNotAWholeNumberOfMonths() {
            assertThatThrownBy(() -> DEFAULT_RETENTION.withKeepMonths(0)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> DEFAULT_RETENTION.withKeepMonths(0))
                    .hasMessageContaining("whole number of months");
            // A fractional period cannot be written: every period is an int.
            assertThat(Signatures.parameterTypes(Retention.class, "<init>"))
                    .containsExactly(List.of(int.class, int.class, int.class));
            assertThat(Signatures.parameterTypes(Retention.class, "withKeepMonths"))
                    .containsExactly(List.of(int.class));
        }

        @Test
        void refusesToClearNotesLaterThanTheRowsGo() {
            assertThatThrownBy(() -> DEFAULT_RETENTION.withKeepNoteMonths(24).withKeepMonths(12))
                    .hasMessageContaining("cannot be longer than the row is kept");
        }

        @Test
        void refusesAFloorAboveTheNoteWindow() {
            assertThatThrownBy(() -> DEFAULT_RETENTION.withKeepNoteMonths(12).withFloorMonths(24))
                    .hasMessageContaining("cannot be longer than notes are kept");
        }
    }

    @Nested
    class PlanningAPrune {

        @Test
        void namesTheCutoffsItUsed() {
            RetentionPlan result = plan(List.of());
            assertThat(result.cutoff()).hasToString("2023-06-16");
            assertThat(result.noteCutoff()).hasToString("2024-06-16");
        }

        @Test
        void keepsAnythingInsideTheFloorWhateverElseItHolds() {
            RetentionPlan result = plan(List.of(at("2025-05-01T09:00:00Z", "a note")));
            assertThat(result.kept()).isEqualTo(1);
            assertThat(result.toRedact()).isEmpty();
        }

        @Test
        void clearsTheNoteOnARowPastTheNoteWindow() {
            RetentionPlan result = plan(List.of(at("2024-01-05T09:00:00Z", "a note")));
            assertThat(result.toRedact()).hasSize(1);
            assertThat(result.expired()).isEmpty();
        }

        @Test
        void leavesARowWithNoNoteAlone() {
            RetentionPlan result = plan(List.of(at("2024-01-05T09:00:00Z")));
            assertThat(result.toRedact()).isEmpty();
            assertThat(result.kept()).isEqualTo(1);
        }

        @Test
        void removesARowPastTheKeepWindow() {
            RetentionPlan result = plan(List.of(at("2022-01-05T09:00:00Z", "a note")));
            assertThat(result.expired()).hasSize(1);
            assertThat(result.toRedact()).isEmpty();
        }

        @Test
        void readsTheDateInTheZoneItWasGiven() {
            CheckIn entry = at("2023-06-15T23:30:00Z");
            assertThat(POLICY.plan(List.of(entry), TODAY, "UTC").expired()).hasSize(1);
            assertThat(POLICY.plan(List.of(entry), TODAY, "Europe/Oslo").expired())
                    .isEmpty();
        }

        @Test
        void sortsAMixedPileIntoTheThreeBuckets() {
            RetentionPlan result = plan(List.of(
                    at("2025-05-01T09:00:00Z", "recent"),
                    at("2024-01-05T09:00:00Z", "middling"),
                    at("2022-01-05T09:00:00Z", "ancient")));
            assertThat(result.describe()).isEqualTo("1 to remove, 1 notes to clear, 1 kept");
        }
    }

    @Nested
    class Redacting {

        @Test
        void clearsTheNoteAndLeavesEverythingElse() {
            CheckIn entry = at("2024-01-05T09:00:00Z", "something personal");
            CheckIn cleared = Retention.redact(entry);
            assertThat(cleared.note()).isEmpty();
            assertThat(cleared.id()).isEqualTo(entry.id());
            assertThat(cleared.reading()).isEqualTo(entry.reading());
        }
    }
}
