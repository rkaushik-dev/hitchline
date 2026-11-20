package io.github.rkaushikdev.hitchline.domain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DateRangeTest {

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static List<String> show(List<DateRange> ranges) {
        return ranges.stream().map(DateRange::toString).toList();
    }

    @Nested
    class Construction {

        @Test
        void parsesAndFormats() {
            assertThat(r("2025-01-06..2025-01-19").toString()).isEqualTo("2025-01-06..2025-01-19");
        }

        @Test
        void refusesARangeThatEndsBeforeItStarts() {
            assertThatThrownBy(() -> DateRange.of(d("2025-01-19"), d("2025-01-06")))
                    .hasMessageContaining("before it starts");
        }

        @Test
        void acceptsASingleDay() {
            assertThat(DateRange.singleDay(d("2025-01-06")).length()).isEqualTo(1);
        }

        @Test
        void refusesAMalformedString() {
            assertThatThrownBy(() -> DateRange.parse("2025-01-06")).hasMessageContaining("YYYY-MM-DD..YYYY-MM-DD");
        }
    }

    @Nested
    class LengthAndMembership {

        @Test
        void countsBothEnds() {
            assertThat(r("2025-01-06..2025-01-19").length()).isEqualTo(14);
        }

        @Test
        void includesTheFirstAndLastDay() {
            DateRange hitch = r("2025-01-06..2025-01-19");
            assertThat(hitch.contains(d("2025-01-06"))).isTrue();
            assertThat(hitch.contains(d("2025-01-19"))).isTrue();
            assertThat(hitch.contains(d("2025-01-20"))).isFalse();
            assertThat(hitch.contains(d("2025-01-05"))).isFalse();
        }

        @Test
        void knowsWhenOneRangeSitsInsideAnother() {
            assertThat(r("2025-01-01..2025-01-31").covers(r("2025-01-06..2025-01-19")))
                    .isTrue();
            assertThat(r("2025-01-06..2025-01-19").covers(r("2025-01-01..2025-01-31")))
                    .isFalse();
        }

        @Test
        void listsEveryDayItCovers() {
            assertThat(r("2025-01-06..2025-01-09").days().stream().map(CivilDate::toString))
                    .containsExactly("2025-01-06", "2025-01-07", "2025-01-08", "2025-01-09");
        }
    }

    @Nested
    class Overlap {

        @Test
        void spotsAnIntersection() {
            assertThat(r("2025-01-06..2025-01-19").intersects(r("2025-01-19..2025-02-01")))
                    .isTrue();
            assertThat(r("2025-01-06..2025-01-19").intersects(r("2025-01-20..2025-02-01")))
                    .isFalse();
        }

        @Test
        void countsAbuttingRangesAsTouchingButNotIntersecting() {
            assertThat(r("2025-01-06..2025-01-19").touches(r("2025-01-20..2025-02-01")))
                    .isTrue();
            assertThat(r("2025-01-06..2025-01-19").touches(r("2025-01-21..2025-02-01")))
                    .isFalse();
        }

        @Test
        void returnsTheOverlappingPart() {
            assertThat(r("2025-01-06..2025-01-19").intersect(r("2025-01-13..2025-02-01")))
                    .map(DateRange::toString)
                    .hasValue("2025-01-13..2025-01-19");
        }

        @Test
        void returnsNothingWhenTheyMiss() {
            assertThat(r("2025-01-06..2025-01-19").intersect(r("2025-02-01..2025-02-14")))
                    .isEmpty();
        }

        @Test
        void clampsToBounds() {
            assertThat(r("2024-12-20..2025-01-19").clamp(r("2025-01-01..2025-12-31")))
                    .map(DateRange::toString)
                    .hasValue("2025-01-01..2025-01-19");
        }
    }

    @Nested
    class ShiftingAndOrdering {

        @Test
        void movesBothEndsTogether() {
            assertThat(r("2025-01-06..2025-01-19").shift(28).toString()).isEqualTo("2025-02-03..2025-02-16");
        }

        @Test
        void ordersByStartThenByEnd() {
            assertThat(DateRange.compare(r("2025-01-06..2025-01-19"), r("2025-01-07..2025-01-19")))
                    .isEqualTo(-1);
            assertThat(DateRange.compare(r("2025-01-06..2025-01-19"), r("2025-01-06..2025-01-12")))
                    .isEqualTo(1);
            assertThat(DateRange.compare(r("2025-01-06..2025-01-19"), r("2025-01-06..2025-01-19")))
                    .isZero();
        }
    }

    @Nested
    class Merge {

        @Test
        void joinsOverlappingAndAbuttingEntries() {
            List<DateRange> merged = DateRange.merge(
                    List.of(r("2025-01-13..2025-01-19"), r("2025-01-06..2025-01-12"), r("2025-02-01..2025-02-07")));
            assertThat(show(merged)).containsExactly("2025-01-06..2025-01-19", "2025-02-01..2025-02-07");
        }

        @Test
        void keepsARangeSwallowedByALongerOne() {
            List<DateRange> merged = DateRange.merge(List.of(r("2025-01-01..2025-01-31"), r("2025-01-10..2025-01-12")));
            assertThat(show(merged)).containsExactly("2025-01-01..2025-01-31");
        }

        @Test
        void handlesAnEmptyList() {
            assertThat(DateRange.merge(List.of())).isEmpty();
        }
    }

    @Nested
    class Subtract {

        @Test
        void cutsAHoleOutOfTheMiddle() {
            List<DateRange> left = r("2025-01-01..2025-01-31").subtract(List.of(r("2025-01-10..2025-01-12")));
            assertThat(show(left)).containsExactly("2025-01-01..2025-01-09", "2025-01-13..2025-01-31");
        }

        @Test
        void trimsFromTheFrontAndTheBack() {
            List<DateRange> left = r("2025-01-01..2025-01-31")
                    .subtract(List.of(r("2024-12-20..2025-01-05"), r("2025-01-28..2025-02-10")));
            assertThat(show(left)).containsExactly("2025-01-06..2025-01-27");
        }

        @Test
        void returnsNothingWhenTheHoleCoversEverything() {
            assertThat(r("2025-01-01..2025-01-31").subtract(List.of(r("2024-12-01..2025-12-31"))))
                    .isEmpty();
        }

        @Test
        void leavesTheRangeAloneWhenTheHolesMiss() {
            List<DateRange> left = r("2025-01-01..2025-01-31").subtract(List.of(r("2025-06-01..2025-06-30")));
            assertThat(show(left)).containsExactly("2025-01-01..2025-01-31");
        }
    }

    @Nested
    class Chunk {

        @Test
        void splitsIntoEqualChunks() {
            assertThat(show(r("2025-01-01..2025-01-12").chunk(4)))
                    .containsExactly("2025-01-01..2025-01-04", "2025-01-05..2025-01-08", "2025-01-09..2025-01-12");
        }

        @Test
        void leavesAShortChunkAtTheEnd() {
            assertThat(show(r("2025-01-01..2025-01-10").chunk(4)))
                    .containsExactly("2025-01-01..2025-01-04", "2025-01-05..2025-01-08", "2025-01-09..2025-01-10");
        }

        @Test
        void refusesAChunkSizeBelowOne() {
            assertThatThrownBy(() -> r("2025-01-01..2025-01-10").chunk(0)).hasMessageContaining("above zero");
        }
    }
}
