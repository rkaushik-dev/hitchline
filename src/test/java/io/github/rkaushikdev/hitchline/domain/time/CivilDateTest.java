package io.github.rkaushikdev.hitchline.domain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.support.Signatures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CivilDateTest {

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    @Nested
    class Construction {

        @Test
        void acceptsARealDate() {
            CivilDate date = CivilDate.of(2025, 2, 28);
            assertThat(List.of(date.year(), date.month(), date.day())).containsExactly(2025, 2, 28);
        }

        @Test
        void acceptsTheLeapDayOnlyInALeapYear() {
            assertThatCode(() -> CivilDate.of(2024, 2, 29)).doesNotThrowAnyException();
            assertThatThrownBy(() -> CivilDate.of(2025, 2, 29)).isInstanceOf(ValidationException.class);
        }

        @Test
        void knowsTheCenturyRuleForLeapYears() {
            assertThat(CivilDate.isLeapYear(1900)).isFalse();
            assertThat(CivilDate.isLeapYear(2000)).isTrue();
            assertThat(CivilDate.isLeapYear(2024)).isTrue();
            assertThat(CivilDate.isLeapYear(2025)).isFalse();
        }

        @Test
        void rejectsAMonthOutsideTheYear() {
            assertThatThrownBy(() -> CivilDate.of(2025, 13, 1)).hasMessageContaining("between 1 and 12");
            assertThatThrownBy(() -> CivilDate.daysInMonth(2025, 0)).hasMessageContaining("between 1 and 12");
        }

        @Test
        void rejectsFractionalParts() {
            // A day of 1.5 cannot be written: every part of a date is an int.
            assertThat(Signatures.parameterTypes(CivilDate.class, "of"))
                    .containsExactly(List.of(int.class, int.class, int.class));
            assertThat(Signatures.parameterTypes(CivilDate.class, "<init>"))
                    .containsExactly(List.of(int.class, int.class, int.class));
        }

        @Test
        void rejectsAYearOutsideTheSupportedRange() {
            assertThatThrownBy(() -> CivilDate.of(1899, 1, 1)).hasMessageContaining("between 1900 and 2999");
        }
    }

    @Nested
    class ParsingAndFormatting {

        @Test
        void roundTripsAnIsoDate() {
            assertThat(CivilDate.parse("2025-03-09").toString()).isEqualTo("2025-03-09");
        }

        @Test
        void padsSingleDigitMonthsAndDays() {
            assertThat(CivilDate.of(2025, 4, 7).toString()).isEqualTo("2025-04-07");
        }

        @Test
        void refusesAnythingThatIsNotAPlainIsoDate() {
            assertThatThrownBy(() -> CivilDate.parse("9 March 2025")).hasMessageContaining("YYYY-MM-DD");
            assertThatThrownBy(() -> CivilDate.parse("2025-3-9")).hasMessageContaining("YYYY-MM-DD");
            assertThatThrownBy(() -> CivilDate.parse("2025-03-09T00:00:00Z")).hasMessageContaining("YYYY-MM-DD");
        }

        @Test
        void stillValidatesAWellShapedButImpossibleDate() {
            assertThatThrownBy(() -> CivilDate.parse("2025-02-30")).hasMessageContaining("between 1 and 28");
        }
    }

    @Nested
    class EpochDays {

        @Test
        void putsTheEpochAtZero() {
            assertThat(d("1970-01-01").epochDay()).isZero();
            assertThat(CivilDate.ofEpochDay(0)).isEqualTo(d("1970-01-01"));
        }

        @Test
        void countsBackwardsBeforeTheEpoch() {
            assertThat(d("1969-12-31").epochDay()).isEqualTo(-1);
            assertThat(CivilDate.ofEpochDay(-1)).isEqualTo(d("1969-12-31"));
        }

        @Test
        void roundTripsAcrossALeapDay() {
            CivilDate leap = d("2024-02-29");
            assertThat(CivilDate.ofEpochDay(leap.epochDay())).isEqualTo(leap);
        }

        @Test
        void rejectsAFractionalEpochDay() {
            // An epoch day of 1.5 cannot be written: the count is a long.
            assertThat(Signatures.parameterTypes(CivilDate.class, "ofEpochDay")).containsExactly(List.of(long.class));
        }
    }

    @Nested
    class Arithmetic {

        @Test
        void addsDaysAcrossAMonthBoundary() {
            assertThat(d("2025-01-30").plusDays(3)).isEqualTo(d("2025-02-02"));
        }

        @Test
        void subtractsDaysAcrossAYearBoundary() {
            assertThat(d("2025-01-02").plusDays(-3)).isEqualTo(d("2024-12-30"));
        }

        @Test
        void clampsTheDayWhenAddingMonthsLandsOnAShortOne() {
            assertThat(d("2025-01-31").plusMonths(1)).isEqualTo(d("2025-02-28"));
            assertThat(d("2024-01-31").plusMonths(1)).isEqualTo(d("2024-02-29"));
        }

        @Test
        void walksBackwardsOverMonths() {
            assertThat(d("2025-03-15").plusMonths(-4)).isEqualTo(d("2024-11-15"));
        }

        @Test
        void measuresTheGapBetweenTwoDates() {
            assertThat(CivilDate.daysBetween(d("2025-01-01"), d("2025-01-15"))).isEqualTo(14);
            assertThat(CivilDate.daysBetween(d("2025-01-15"), d("2025-01-01"))).isEqualTo(-14);
        }

        @Test
        void countsAnInclusiveSpan() {
            assertThat(CivilDate.dayCount(d("2025-01-01"), d("2025-01-14"))).isEqualTo(14);
            assertThat(CivilDate.dayCount(d("2025-01-14"), d("2025-01-01"))).isZero();
        }
    }

    @Nested
    class Comparison {

        @Test
        void ordersDates() {
            assertThat(CivilDate.compare(d("2025-01-01"), d("2025-01-02"))).isEqualTo(-1);
            assertThat(CivilDate.compare(d("2025-01-02"), d("2025-01-01"))).isEqualTo(1);
            assertThat(CivilDate.compare(d("2025-01-01"), d("2025-01-01"))).isZero();
        }

        @Test
        void answersTheReadableQuestions() {
            assertThat(d("2025-01-01").isBefore(d("2025-01-02"))).isTrue();
            assertThat(d("2025-01-01").isAfter(d("2025-01-02"))).isFalse();
            assertThat(d("2025-01-01").isSameDay(d("2025-01-01"))).isTrue();
        }

        @Test
        void treatsARangeAsClosedAtBothEnds() {
            assertThat(d("2025-01-01").isWithin(d("2025-01-01"), d("2025-01-14")))
                    .isTrue();
            assertThat(d("2025-01-14").isWithin(d("2025-01-01"), d("2025-01-14")))
                    .isTrue();
            assertThat(d("2025-01-15").isWithin(d("2025-01-01"), d("2025-01-14")))
                    .isFalse();
        }

        @Test
        void picksTheEarlierAndLaterOfAPair() {
            assertThat(CivilDate.min(d("2025-05-01"), d("2025-04-01"))).isEqualTo(d("2025-04-01"));
            assertThat(CivilDate.max(d("2025-05-01"), d("2025-04-01"))).isEqualTo(d("2025-05-01"));
        }
    }

    @Nested
    class Weekdays {

        @Test
        void numbersMondayAsOneAndSundayAsSeven() {
            assertThat(d("2025-03-10").dayOfWeek()).isEqualTo(1);
            assertThat(d("2025-03-16").dayOfWeek()).isEqualTo(7);
        }

        @Test
        void worksBeforeTheEpoch() {
            assertThat(d("1969-12-31").dayOfWeek()).isEqualTo(3);
        }

        @Test
        void flagsSaturdayAndSunday() {
            assertThat(d("2025-03-15").isWeekend()).isTrue();
            assertThat(d("2025-03-16").isWeekend()).isTrue();
            assertThat(d("2025-03-17").isWeekend()).isFalse();
        }

        @Test
        void findsTheEndsOfAnIsoWeek() {
            assertThat(d("2025-03-13").startOfIsoWeek()).isEqualTo(d("2025-03-10"));
            assertThat(d("2025-03-13").endOfIsoWeek()).isEqualTo(d("2025-03-16"));
            assertThat(d("2025-03-16").startOfIsoWeek()).isEqualTo(d("2025-03-10"));
        }
    }

    @Nested
    class MonthEdges {

        @Test
        void findsTheFirstAndLastDay() {
            assertThat(d("2025-02-14").startOfMonth()).isEqualTo(d("2025-02-01"));
            assertThat(d("2025-02-14").endOfMonth()).isEqualTo(d("2025-02-28"));
            assertThat(d("2024-02-14").endOfMonth()).isEqualTo(d("2024-02-29"));
        }
    }

    @Nested
    class EachDay {

        @Test
        void walksAnInclusiveRange() {
            assertThat(CivilDate.eachDay(d("2025-01-01"), d("2025-01-04")).stream()
                            .map(CivilDate::toString))
                    .containsExactly("2025-01-01", "2025-01-02", "2025-01-03", "2025-01-04");
        }

        @Test
        void yieldsASingleDayWhenBothEndsMatch() {
            assertThat(CivilDate.eachDay(d("2025-01-01"), d("2025-01-01"))).hasSize(1);
        }

        @Test
        void yieldsNothingWhenTheRangeIsReversed() {
            assertThat(CivilDate.eachDay(d("2025-01-04"), d("2025-01-01"))).isEmpty();
        }
    }
}
