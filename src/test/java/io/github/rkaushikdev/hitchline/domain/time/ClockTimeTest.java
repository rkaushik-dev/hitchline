package io.github.rkaushikdev.hitchline.domain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.support.Signatures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClockTimeTest {

    private static ClockTime t(String text) {
        return ClockTime.parse(text);
    }

    private static ClockRange r(String text) {
        return ClockRange.parse(text);
    }

    @Nested
    class TheClockTime {

        @Test
        void buildsATimeFromParts() {
            ClockTime time = ClockTime.of(6, 45);
            assertThat(List.of(time.hour(), time.minute())).containsExactly(6, 45);
        }

        @Test
        void rejectsAnHourOrMinuteOutOfRange() {
            assertThatThrownBy(() -> ClockTime.of(24, 0)).hasMessageContaining("between 0 and 23");
            assertThatThrownBy(() -> ClockTime.of(6, 60)).hasMessageContaining("between 0 and 59");
        }

        @Test
        void rejectsFractionalParts() {
            // A minute of 30.5 cannot be written: hour and minute are ints.
            assertThat(Signatures.parameterTypes(ClockTime.class, "of")).containsExactly(List.of(int.class, int.class));
            assertThat(Signatures.parameterTypes(ClockTime.class, "<init>"))
                    .containsExactly(List.of(int.class, int.class));
        }

        @Test
        void parsesAndFormatsAPaddedTime() {
            assertThat(t("06:05").toString()).isEqualTo("06:05");
            assertThat(ClockTime.of(23, 59).toString()).isEqualTo("23:59");
        }

        @Test
        void refusesALooseTimeString() {
            assertThatThrownBy(() -> t("6:05")).hasMessageContaining("HH:MM");
            assertThatThrownBy(() -> t("24:00")).hasMessageContaining("HH:MM");
            assertThatThrownBy(() -> t("06:60")).hasMessageContaining("HH:MM");
            assertThatThrownBy(() -> t("quarter past")).hasMessageContaining("HH:MM");
        }
    }

    @Nested
    class MinutesSinceMidnight {

        @Test
        void countsFromZero() {
            assertThat(ClockTime.MIDNIGHT.minutesSinceMidnight()).isZero();
            assertThat(t("01:30").minutesSinceMidnight()).isEqualTo(90);
            assertThat(t("23:59").minutesSinceMidnight()).isEqualTo(ClockTime.MINUTES_IN_DAY - 1);
        }

        @Test
        void roundTrips() {
            assertThat(ClockTime.fromMinutesSinceMidnight(90)).isEqualTo(t("01:30"));
            assertThat(ClockTime.fromMinutesSinceMidnight(0)).isEqualTo(ClockTime.MIDNIGHT);
        }

        @Test
        void wrapsAValuePastTheEndOfTheDay() {
            assertThat(ClockTime.fromMinutesSinceMidnight(ClockTime.MINUTES_IN_DAY + 15))
                    .isEqualTo(t("00:15"));
            assertThat(ClockTime.fromMinutesSinceMidnight(-15)).isEqualTo(t("23:45"));
        }

        @Test
        void rejectsAFractionalMinuteCount() {
            // A count of 1.5 minutes cannot be written: the count is an int.
            assertThat(Signatures.parameterTypes(ClockTime.class, "fromMinutesSinceMidnight"))
                    .containsExactly(List.of(int.class));
        }
    }

    @Nested
    class AddingMinutes {

        @Test
        void movesWithinTheDay() {
            assertThat(t("06:00").plusMinutes(90)).isEqualTo(t("07:30"));
        }

        @Test
        void wrapsPastMidnightAndReportsTheShift() {
            assertThat(t("23:30").plusMinutes(60)).isEqualTo(t("00:30"));
            assertThat(t("23:30").dayShift(60)).isEqualTo(1);
            assertThat(t("06:00").dayShift(90)).isZero();
            assertThat(t("00:30").dayShift(-60)).isEqualTo(-1);
        }
    }

    @Nested
    class Comparison {

        @Test
        void ordersTwoTimes() {
            assertThat(ClockTime.compare(t("06:00"), t("07:00"))).isEqualTo(-1);
            assertThat(ClockTime.compare(t("07:00"), t("06:00"))).isEqualTo(1);
            assertThat(ClockTime.compare(t("07:00"), t("07:00"))).isZero();
        }
    }

    @Nested
    class Ranges {

        @Test
        void parsesAndFormats() {
            assertThat(r("22:00-06:00").toString()).isEqualTo("22:00-06:00");
        }

        @Test
        void refusesAMalformedRange() {
            assertThatThrownBy(() -> r("22:00")).hasMessageContaining("HH:MM-HH:MM");
            assertThatThrownBy(() -> r("22:00-06:00-07:00")).hasMessageContaining("HH:MM-HH:MM");
        }

        @Test
        void refusesAnEmptyRange() {
            assertThatThrownBy(() -> ClockRange.of(t("06:00"), t("06:00"))).hasMessageContaining("same minute");
        }

        @Test
        void knowsWhenItWrapsPastMidnight() {
            assertThat(r("22:00-06:00").wrapsMidnight()).isTrue();
            assertThat(r("06:00-22:00").wrapsMidnight()).isFalse();
        }

        @Test
        void measuresItsLengthEitherWayRound() {
            assertThat(r("06:00-22:00").lengthMinutes()).isEqualTo(960);
            assertThat(r("22:00-06:00").lengthMinutes()).isEqualTo(480);
        }

        @Test
        void isClosedAtTheStartAndOpenAtTheEnd() {
            ClockRange day = r("06:00-22:00");
            assertThat(day.contains(t("06:00"))).isTrue();
            assertThat(day.contains(t("21:59"))).isTrue();
            assertThat(day.contains(t("22:00"))).isFalse();
            assertThat(day.contains(t("05:59"))).isFalse();
        }

        @Test
        void handlesARangeThatWrapsPastMidnight() {
            ClockRange night = r("22:00-06:00");
            assertThat(night.contains(t("23:30"))).isTrue();
            assertThat(night.contains(t("02:00"))).isTrue();
            assertThat(night.contains(t("06:00"))).isFalse();
            assertThat(night.contains(t("12:00"))).isFalse();
        }

        @Test
        void detectsOverlapBetweenTwoRanges() {
            assertThat(r("22:00-06:00").overlaps(r("05:00-09:00"))).isTrue();
            assertThat(r("22:00-06:00").overlaps(r("07:00-09:00"))).isFalse();
            assertThat(r("06:00-22:00").overlaps(r("06:00-22:00"))).isTrue();
        }
    }
}
