package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordResult;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.MutableClock;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CheckInsServiceTest {

    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String ADA = Fixtures.aMember().id();

    private MutableClock clock;
    private MemoryStore store;
    private CheckInsService service;

    @BeforeEach
    void create() {
        clock = Clocks.fixedClock("2025-01-10T09:00:00Z");
        store = MemoryStore.create(clock, IdSources.sequentialIds());
        service = new CheckInsService(store);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/day")));
    }

    private RecordResult record(RecordCheckInCommand command) {
        return service.record(command);
    }

    private static RecordCheckInCommand treadmill() {
        return RecordCheckInCommand.of(TREADMILL);
    }

    @Nested
    class ReadingsLongerThanTheDay {

        private static final String REST = Ids.habitId("hab_FEDCBA9876543210");

        private void restHabit(String target) {
            store.habits().save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                    .cadence("1/day")
                    .target(target)
                    .startsOn("2025-01-01")));
        }

        private RecordCheckInCommand rest(String at, double reading) {
            return RecordCheckInCommand.of(REST).withAt(at).withReading(reading).withSource(CheckInSource.SUPERVISOR);
        }

        @Test
        void refusesMinutesThatAddUpPastTheDay() {
            restHabit("at least 600 minutes");
            record(rest("2025-01-08T02:00:00Z", 1000));
            assertThatThrownBy(() -> record(rest("2025-01-08T16:00:00Z", 500)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("takes 2025-01-08 to 1500 minutes, more than the 1440 the day holds");
            assertThat(record(rest("2025-01-08T16:00:00Z", 440)).accepted()).isTrue();
        }

        @Test
        void holdsTheDayTheClocksGoForwardToTwentyThreeHours() {
            restHabit("at least 10 hours");
            clock.set(Zones.parseInstant("2025-04-01T09:00:00Z"));
            assertThatThrownBy(() -> record(rest("2025-03-30T12:00:00Z", 23.5)))
                    .hasMessageContaining("more than the 23 the day holds");
            assertThat(record(rest("2025-03-30T12:00:00Z", 23)).accepted()).isTrue();
        }

        @Test
        void leavesOutAVoidedReading() {
            restHabit("at least 10 hours");
            CheckIn wrong = record(rest("2025-01-08T02:00:00Z", 20)).entry();
            service.voidEntry(wrong.id());
            assertThat(record(rest("2025-01-08T12:00:00Z", 12)).accepted()).isTrue();
        }

        @Test
        void holdsAnAmendmentToTheDayWithoutCountingTheOldReading() {
            restHabit("at least 10 hours");
            CheckIn first = record(rest("2025-01-08T02:00:00Z", 8)).entry();
            record(rest("2025-01-08T14:00:00Z", 6));
            assertThat(service.amend(first.id(), OptionalDouble.of(18)).reading())
                    .isEqualTo(OptionalDouble.of(18));
            assertThatThrownBy(() -> service.amend(first.id(), OptionalDouble.of(18.5)))
                    .hasMessageContaining("takes 2025-01-08 to 24.5 hours");
        }

        @Test
        void putsNoCeilingOnAUnitThatIsNotTime() {
            restHabit("at least 2 litres");
            assertThat(record(rest("2025-01-08T02:00:00Z", 3000)).accepted()).isTrue();
            assertThat(record(treadmill().withReading(5000)).accepted()).isTrue();
        }

        @Test
        void letsARepeatedTapThroughWithoutCountingIt() {
            restHabit("at least 10 hours");
            record(rest("2025-01-08T02:00:00Z", 14));
            assertThat(record(rest("2025-01-08T02:01:00Z", 14)).accepted()).isTrue();
            assertThat(record(rest("2025-01-08T12:00:00Z", 10)).accepted()).isTrue();
            assertThatThrownBy(() -> record(rest("2025-01-08T18:00:00Z", 0.5))).hasMessageContaining("to 24.5 hours");
        }

        @Test
        void answersARetryWithTheStoredRowRatherThanRefusingIt() {
            restHabit("at least 10 hours");
            RecordCheckInCommand once = rest("2025-01-08T02:00:00Z", 20).withIdempotencyKey("rest-0108-a");
            CheckIn stored = record(once).entry();
            RecordResult retry = record(once);
            assertThat(retry.accepted()).isFalse();
            assertThat(retry.entry()).isEqualTo(stored);
        }
    }

    @Nested
    class CheckInsOnAHeldOverDay {

        @Test
        void listThemOnTheSiteClock() {
            new RosterService(store)
                    .hold(new RosterService.HoldCommand(
                            ADA,
                            "2025-01-20..2025-01-22",
                            DayRole.ONSHIFT,
                            OverrideReason.HELD_OVER,
                            Optional.empty()));
            clock.set(Zones.parseInstant("2025-01-23T09:00:00Z"));
            record(treadmill().withAt("2025-01-21T06:00:00Z"));
            assertThat(service.listFor(ADA).get(0).day().zone()).isEqualTo("Europe/Oslo");
            assertThat(service.listFor(ADA).get(0).day().role().wire()).isEqualTo("onshift");
        }
    }

    @Nested
    class RecordingACheckIn {

        @Test
        void stampsItWithTheClockWhenNoTimeIsGiven() {
            RecordResult result = record(treadmill());
            assertThat(result.accepted()).isTrue();
            assertThat(result.entry().at()).isEqualTo(clock.now());
            assertThat(result.entry().recordedAt()).isEqualTo(clock.now());
        }

        @Test
        void mintsAnIdAndStoresIt() {
            CheckIn entry = record(treadmill()).entry();
            assertThat(entry.id()).startsWith("chk_");
            assertThat(store.checkIns().byId(entry.id())).hasValue(entry);
        }

        @Test
        void keepsTheReadingAndTheNote() {
            CheckIn entry = record(treadmill().withReading(25).withNote("before the shift"))
                    .entry();
            assertThat(entry.reading()).hasValue(25);
            assertThat(entry.note()).isEqualTo("before the shift");
        }

        @Test
        void takesATimeInThePast() {
            CheckIn entry = record(treadmill().withAt("2025-01-08T06:00:00Z")).entry();
            assertThat(entry.at()).isBefore(entry.recordedAt());
        }

        @Test
        void refusesATimeInTheFuture() {
            assertThatThrownBy(() -> record(treadmill().withAt("2025-01-11T09:00:00Z")))
                    .hasMessageContaining("in the future");
        }

        @Test
        void allowsAFewMinutesOfHandsetDrift() {
            assertThatCode(() -> record(treadmill().withAt("2025-01-10T09:02:00Z")))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesABackfillPastTheLimit() {
            assertThatThrownBy(() -> record(treadmill().withAt("2024-12-01T09:00:00Z")))
                    .hasMessageContaining(CheckInsService.BACKFILL_LIMIT_DAYS + " day limit");
        }

        @Test
        void letsASupervisorOrAnImportGoFurtherBack() {
            assertThatCode(() ->
                            record(treadmill().withAt("2025-01-02T09:00:00Z").withSource(CheckInSource.SUPERVISOR)))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesADayTheHabitWasNotRunning() {
            store.habits().save(Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-09")));
            assertThatThrownBy(() -> record(treadmill().withAt("2025-01-08T09:00:00Z")))
                    .hasMessageContaining("not running");
        }

        @Test
        void refusesAHabitItHasNeverHeardOf() {
            assertThatThrownBy(() -> record(RecordCheckInCommand.of(Ids.habitId("hab_9999999999999999"))))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Idempotency {

        @Test
        void givesBackTheFirstEntryWhenAKeyRepeats() {
            RecordResult first = record(treadmill().withIdempotencyKey("watch-0110"));
            RecordResult second = record(treadmill().withIdempotencyKey("watch-0110"));
            assertThat(second.accepted()).isFalse();
            assertThat(second.entry().id()).isEqualTo(first.entry().id());
            assertThat(store.checkIns().byHabit(TREADMILL)).hasSize(1);
        }

        @Test
        void acceptsTheSameKeyOnAnotherHabit() {
            store.habits()
                    .save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210").cadence("1/day")));
            record(treadmill().withIdempotencyKey("watch-0110"));
            RecordResult other = record(
                    RecordCheckInCommand.of(Ids.habitId("hab_FEDCBA9876543210")).withIdempotencyKey("watch-0110"));
            assertThat(other.accepted()).isTrue();
        }
    }

    @Nested
    class VoidingAndAmending {

        @Test
        void voidsAnEntryAndStampsWhen() {
            CheckIn entry = record(treadmill()).entry();
            assertThat(service.voidEntry(entry.id()).voidedAt()).hasValue(clock.now());
        }

        @Test
        void refusesToVoidTheSameEntryTwice() {
            CheckIn entry = record(treadmill()).entry();
            service.voidEntry(entry.id());
            assertThatThrownBy(() -> service.voidEntry(entry.id())).isInstanceOf(ConflictException.class);
        }

        @Test
        void amendsAReading() {
            CheckIn entry = record(treadmill().withReading(20)).entry();
            assertThat(service.amend(entry.id(), OptionalDouble.of(25)).reading())
                    .hasValue(25);
        }

        @Test
        void complainsAboutAnIdItDoesNotHold() {
            assertThatThrownBy(() -> service.amend(Ids.checkInId("chk_9999999999999999"), OptionalDouble.of(1)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Listing {

        @BeforeEach
        void seed() {
            record(treadmill().withAt("2025-01-08T06:00:00Z"));
            record(treadmill().withAt("2025-01-10T06:00:00Z"));
        }

        @Test
        void resolvesEachEntryOntoItsRotationDay() {
            assertThat(service.listFor(ADA).stream()
                            .map(item -> item.day().date().toString()))
                    .containsExactly("2025-01-08", "2025-01-10");
        }

        @Test
        void narrowsToAWindow() {
            assertThat(service.listFor(ADA, Optional.of(DateRange.parse("2025-01-09..2025-01-11"))))
                    .hasSize(1);
        }

        @Test
        void leavesVoidedEntriesOut() {
            service.voidEntry(store.checkIns().byMember(ADA).get(0).id());
            assertThat(service.listFor(ADA)).hasSize(1);
        }

        @Test
        void complainsAboutAMemberItDoesNotHold() {
            assertThatThrownBy(() -> service.listFor(Ids.crewMemberId("mem_9999999999999999")))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ValidationPassesThrough {

        @Test
        void rejectsANegativeReading() {
            assertThatThrownBy(() -> record(treadmill().withReading(-1))).isInstanceOf(ValidationException.class);
        }
    }
}
