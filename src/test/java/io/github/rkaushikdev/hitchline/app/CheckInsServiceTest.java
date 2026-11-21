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
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
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
