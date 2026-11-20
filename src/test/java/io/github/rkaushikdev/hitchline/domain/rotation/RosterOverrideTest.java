package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RosterOverrideTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final Instant ENTERED_AT = Zones.parseInstant("2025-01-04T10:00:00Z");

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    /** What a test may change about the default entry, before it is checked. */
    private record Draft(String memberId, DateRange range, OverrideReason reason, String note) {
        Draft member(String value) {
            return new Draft(value, range, reason, note);
        }

        Draft range(String value) {
            return new Draft(memberId, r(value), reason, note);
        }

        Draft reason(OverrideReason value) {
            return new Draft(memberId, range, value, note);
        }

        Draft note(String value) {
            return new Draft(memberId, range, reason, value);
        }
    }

    private static RosterOverride anOverride() {
        return anOverride(UnaryOperator.identity());
    }

    private static RosterOverride anOverride(UnaryOperator<Draft> change) {
        Draft draft = change.apply(new Draft(ADA, r("2025-01-20..2025-01-22"), OverrideReason.HELD_OVER, null));
        return new RosterOverride(draft.memberId(), draft.range(), ONSHIFT, draft.reason(), draft.note(), ENTERED_AT);
    }

    @Nested
    class CreatingAnOverride {

        @Test
        void keepsTheReasonAndTrimsTheNote() {
            RosterOverride entry = anOverride(o -> o.note("  waiting on the relief crew  "));
            assertThat(entry.reason()).isEqualTo(OverrideReason.HELD_OVER);
            assertThat(entry.note()).isEqualTo("waiting on the relief crew");
        }

        @Test
        void defaultsTheNoteToEmpty() {
            assertThat(anOverride().note()).isEmpty();
        }

        @Test
        void refusesANoteLongerThanTheFieldAllows() {
            assertThatThrownBy(() -> anOverride(o -> o.note("x".repeat(241))))
                    .hasMessageContaining("at most 240 characters");
        }

        @Test
        void refusesARunLongerThanFourMonths() {
            assertThatThrownBy(() -> anOverride(o -> o.range("2025-01-01..2025-06-30")))
                    .hasMessageContaining("more than the 120");
        }
    }

    @Nested
    class FindingOverrides {

        private final List<RosterOverride> entries = List.of(
                anOverride(o -> o.range("2025-02-10..2025-02-12").reason(OverrideReason.CALLED_OUT)),
                anOverride(o -> o.range("2025-01-20..2025-01-22")),
                anOverride(o -> o.member(BO).range("2025-01-21..2025-01-23").reason(OverrideReason.SWAPPED)));

        @Test
        void sortsByTheDayTheyStart() {
            assertThat(RosterOverride.sort(entries).stream()
                            .map(entry -> entry.range().toString()))
                    .containsExactly("2025-01-20..2025-01-22", "2025-01-21..2025-01-23", "2025-02-10..2025-02-12");
        }

        @Test
        void findsTheEntryCoveringADayForOnePerson() {
            assertThat(RosterOverride.overrideOn(entries, ADA, d("2025-01-21"))
                            .map(entry -> entry.range().toString()))
                    .hasValue("2025-01-20..2025-01-22");
        }

        @Test
        void doesNotReadAnotherPersonEntry() {
            assertThat(RosterOverride.overrideOn(entries, ADA, d("2025-01-23"))).isEmpty();
            assertThat(RosterOverride.overrideOn(entries, BO, d("2025-01-23")).map(RosterOverride::reason))
                    .hasValue(OverrideReason.SWAPPED);
        }

        @Test
        void listsWhatTouchesAWindow() {
            assertThat(RosterOverride.overridesOver(entries, ADA, r("2025-01-01..2025-01-31")))
                    .hasSize(1);
            assertThat(RosterOverride.overridesOver(entries, ADA, r("2025-03-01..2025-03-31")))
                    .isEmpty();
        }
    }

    @Nested
    class Clashes {

        @Test
        void acceptsEntriesThatDoNotTouch() {
            assertThatCode(() -> RosterOverride.checkNoClashes(List.of(
                            anOverride(o -> o.range("2025-01-20..2025-01-22")),
                            anOverride(o -> o.range("2025-01-23..2025-01-25")))))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsTwoEntriesCoveringTheSameDayForOnePerson() {
            assertThatThrownBy(() -> RosterOverride.checkNoClashes(List.of(
                            anOverride(o -> o.range("2025-01-20..2025-01-22")),
                            anOverride(o -> o.range("2025-01-22..2025-01-25")))))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void allowsTwoPeopleToBeOverriddenOnTheSameDay() {
            assertThatCode(() -> RosterOverride.checkNoClashes(
                            List.of(anOverride(o -> o.range("2025-01-20..2025-01-22")), anOverride(o -> o.member(BO)
                                    .range("2025-01-20..2025-01-22")))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class ReadingAnOverride {

        @Test
        void saysWhichDaysItCovers() {
            RosterOverride entry = anOverride();
            assertThat(entry.coversDay(d("2025-01-20"))).isTrue();
            assertThat(entry.coversDay(d("2025-01-23"))).isFalse();
            assertThat(entry.firstDay()).isEqualTo("2025-01-20");
        }

        @Test
        void describesItselfWithTheNote() {
            assertThat(anOverride(o -> o.note("relief crew weathered off")).describe())
                    .isEqualTo("2025-01-20..2025-01-22 onshift because held_over (relief crew weathered off)");
        }

        @Test
        void describesItselfWithoutANote() {
            assertThat(anOverride().describe()).isEqualTo("2025-01-20..2025-01-22 onshift because held_over");
        }
    }
}
