package io.github.rkaushikdev.hitchline.domain.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CheckInTest {

    private static final Instant AT = at("2025-01-10T07:30:00Z");
    private static final Instant RECORDED_AT = at("2025-01-10T07:31:00Z");

    private static Instant at(String text) {
        return Zones.parseInstant(text);
    }

    private static CheckIn.Builder a() {
        return a(AT, RECORDED_AT);
    }

    private static CheckIn.Builder a(Instant when, Instant told) {
        return a(Ids.checkInId("chk_0123456789ABCDEF"), when, told);
    }

    private static CheckIn.Builder a(String id, Instant when, Instant told) {
        return CheckIn.builder(
                id,
                Ids.habitId("hab_0123456789ABCDEF"),
                Ids.crewMemberId("mem_0123456789ABCDEF"),
                when,
                told,
                CheckInSource.APP);
    }

    private static CheckIn.Builder toldAt(String text) {
        return a(AT, at(text));
    }

    @Nested
    class Sources {

        @Test
        void knowsTheOnesItAccepts() {
            assertThat(CheckInSource.isCheckInSource("kiosk")).isTrue();
            assertThat(CheckInSource.isCheckInSource("sms")).isFalse();
        }
    }

    @Nested
    class CreatingACheckIn {

        @Test
        void startsUnvoidedWithATrimmedNote() {
            CheckIn entry = a().note("  did it before breakfast  ").build();
            assertThat(entry.note()).isEqualTo("did it before breakfast");
            assertThat(entry.isVoided()).isFalse();
        }

        @Test
        void allowsAReadingOfZero() {
            assertThat(a().reading(0).build().reading()).hasValue(0);
        }

        @Test
        void refusesANegativeOrAbsurdReading() {
            assertThatThrownBy(() -> a().reading(-1).build()).hasMessageContaining("must not be negative");
            assertThatThrownBy(() -> a().reading(100_001).build()).hasMessageContaining("at most 100000");
        }

        @Test
        void refusesANoteLongerThanTheField() {
            assertThatThrownBy(() -> a().note("x".repeat(501)).build()).hasMessageContaining("at most 500 characters");
        }

        @Test
        void refusesARecordingTimeWellBeforeTheEvent() {
            assertThatThrownBy(() -> toldAt("2025-01-10T07:00:00Z").build())
                    .hasMessageContaining("before the check in happened");
        }

        @Test
        void allowsFiveMinutesOfClockSlack() {
            assertThatCode(() -> toldAt("2025-01-10T07:26:00Z").build()).doesNotThrowAnyException();
            assertThatThrownBy(() -> toldAt("2025-01-10T07:24:00Z").build()).isInstanceOf(RuntimeException.class);
        }

        @Test
        void checksTheShapeOfAnIdempotencyKey() {
            assertThatThrownBy(() -> a().idempotencyKey("short").build())
                    .hasMessageContaining("8 to 64 plain characters");
            assertThat(a().idempotencyKey("watch-2025-01-10-0730").build().idempotencyKey())
                    .hasValue("watch-2025-01-10-0730");
        }
    }

    @Nested
    class Voiding {

        @Test
        void recordsWhenItWasVoided() {
            CheckIn voided = a().build().voidAt(at("2025-01-11T09:00:00Z"));
            assertThat(voided.isVoided()).isTrue();
            assertThat(voided.voidedAt()).hasValue(at("2025-01-11T09:00:00Z"));
        }

        @Test
        void leavesAnAlreadyVoidedEntryAlone() {
            CheckIn voided = a().build().voidAt(at("2025-01-11T09:00:00Z"));
            assertThat(voided.voidAt(at("2025-01-12T09:00:00Z"))).isSameAs(voided);
        }

        @Test
        void refusesToVoidBeforeTheEntryWasRecorded() {
            assertThatThrownBy(() -> a().build().voidAt(at("2025-01-09T09:00:00Z")))
                    .hasMessageContaining("before it was recorded");
        }
    }

    @Nested
    class Amending {

        @Test
        void replacesTheReading() {
            assertThat(a().reading(20)
                            .build()
                            .amendReading(OptionalDouble.of(25))
                            .reading())
                    .hasValue(25);
        }

        @Test
        void clearsTheReading() {
            assertThat(a().reading(20)
                            .build()
                            .amendReading(OptionalDouble.empty())
                            .reading())
                    .isEmpty();
        }

        @Test
        void refusesANegativeReading() {
            assertThatThrownBy(() -> a().build().amendReading(OptionalDouble.of(-3)))
                    .hasMessageContaining("must not be negative");
        }

        @Test
        void refusesToAmendAVoidedEntry() {
            CheckIn voided = a().build().voidAt(at("2025-01-11T09:00:00Z"));
            assertThatThrownBy(() -> voided.amendReading(OptionalDouble.of(25)))
                    .hasMessageContaining("voided check in");
        }
    }

    @Nested
    class ReportingDelay {

        @Test
        void countsTheMinutesBetweenDoingAndTelling() {
            assertThat(a().build().reportingDelayMinutes()).isEqualTo(1);
            assertThat(toldAt("2025-01-12T07:30:00Z").build().reportingDelayMinutes())
                    .isEqualTo(2880);
        }

        @Test
        void neverGoesNegativeOnASlackClock() {
            assertThat(toldAt("2025-01-10T07:27:00Z").build().reportingDelayMinutes())
                    .isZero();
        }

        @Test
        void flagsAnEntryThatArrivedMoreThanADayLate() {
            assertThat(a().build().isBackfilled()).isFalse();
            assertThat(toldAt("2025-01-12T07:30:00Z").build().isBackfilled()).isTrue();
        }

        @Test
        void takesATighterThresholdWhenAsked() {
            assertThat(toldAt("2025-01-10T09:30:00Z").build().isBackfilled(60)).isTrue();
        }
    }

    @Nested
    class Ordering {

        @Test
        void sortsByTheTimeItHappened() {
            CheckIn early = a(at("2025-01-10T06:00:00Z"), RECORDED_AT).build();
            CheckIn late =
                    a(at("2025-01-10T08:00:00Z"), at("2025-01-10T08:05:00Z")).build();
            assertThat(CheckIn.compare(early, late)).isEqualTo(-1);
            assertThat(CheckIn.compare(late, early)).isEqualTo(1);
        }

        @Test
        void breaksATieOnTheId() {
            CheckIn first = a().build();
            CheckIn second =
                    a(Ids.checkInId("chk_FEDCBA9876543210"), AT, RECORDED_AT).build();
            assertThat(CheckIn.compare(first, second)).isNegative();
            assertThat(CheckIn.compare(first, first)).isZero();
        }
    }

    @Nested
    class TheClockSlack {

        @Test
        void isTheOneNumberBothTheRecordAndTheServiceUse() {
            assertThat(CheckIn.CLOCK_SLACK_MS).isEqualTo(5 * 60_000L);
        }

        @Test
        void isWhatDecidesWhetherAHandsetReadingIsTooFarAhead() {
            assertThatCode(() -> toldAt("2025-01-10T07:26:00Z").build()).doesNotThrowAnyException();
            assertThatThrownBy(() -> toldAt("2025-01-10T07:24:00Z").build())
                    .hasMessageContaining("before the check in happened");
        }
    }
}
