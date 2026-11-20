package io.github.rkaushikdev.hitchline.domain.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String STRETCHES = Ids.habitId("hab_FEDCBA9876543210");
    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");

    /** What a test may change about the default entry. */
    private record Draft(String id, String habit, String at, Optional<String> key, OptionalDouble reading) {
        Draft id(String value) {
            return new Draft(value, habit, at, key, reading);
        }

        Draft habit(String value) {
            return new Draft(id, value, at, key, reading);
        }

        Draft at(String value) {
            return new Draft(id, habit, value, key, reading);
        }

        Draft key(String value) {
            return new Draft(id, habit, at, Optional.of(value), reading);
        }

        Draft reading(double value) {
            return new Draft(id, habit, at, key, OptionalDouble.of(value));
        }

        CheckIn build() {
            Instant when = Zones.parseInstant(at);
            return CheckIn.builder(Ids.checkInId(id), habit, ADA, when, when, CheckInSource.APP)
                    .reading(reading)
                    .idempotencyKey(key)
                    .build();
        }
    }

    private static Draft draft() {
        return new Draft(
                "chk_0123456789ABCDEF", TREADMILL, "2025-01-10T07:30:00Z", Optional.empty(), OptionalDouble.empty());
    }

    private static CheckIn entry() {
        return draft().build();
    }

    private static Instant at(String text) {
        return Zones.parseInstant(text);
    }

    private static List<String> ids(List<CheckIn> entries) {
        return entries.stream().map(CheckIn::id).toList();
    }

    @Nested
    class AnEmptyLedger {

        @Test
        void holdsNothing() {
            Ledger ledger = Ledger.empty();
            assertThat(ledger.size()).isZero();
            assertThat(ledger.liveEntries()).isEmpty();
            assertThat(ledger.lastEntry()).isEmpty();
        }
    }

    @Nested
    class Appending {

        @Test
        void acceptsANewEntry() {
            AppendResult result = Ledger.empty().append(entry());
            assertThat(result.accepted()).isTrue();
            assertThat(result.ledger().size()).isEqualTo(1);
        }

        @Test
        void refusesTheSameIdTwice() {
            Ledger once = Ledger.empty().append(entry()).ledger();
            assertThatThrownBy(() -> once.append(entry())).isInstanceOf(ConflictException.class);
        }

        @Test
        void keepsEntriesInTheOrderTheyHappened() {
            Ledger ledger = Ledger.empty()
                    .appendAll(List.of(
                            draft().id("chk_0000000000000002")
                                    .at("2025-01-10T18:00:00Z")
                                    .build(),
                            draft().id("chk_0000000000000001")
                                    .at("2025-01-10T06:00:00Z")
                                    .build()));
            assertThat(ids(ledger.entries())).containsExactly("chk_0000000000000001", "chk_0000000000000002");
        }

        @Test
        void sortsALedgerBuiltFromAList() {
            Ledger ledger = Ledger.of(List.of(
                    draft().id("chk_0000000000000002")
                            .at("2025-01-10T18:00:00Z")
                            .build(),
                    draft().id("chk_0000000000000001")
                            .at("2025-01-10T06:00:00Z")
                            .build()));
            assertThat(ledger.lastEntry().map(CheckIn::id)).hasValue("chk_0000000000000002");
        }
    }

    @Nested
    class Idempotency {

        @Test
        void returnsTheEntryAlreadyStoredUnderTheKey() {
            AppendResult first =
                    Ledger.empty().append(draft().key("watch-2025-01-10").build());
            AppendResult second = first.ledger()
                    .append(draft().id("chk_0000000000000009")
                            .key("watch-2025-01-10")
                            .build());
            assertThat(second.accepted()).isFalse();
            assertThat(second.entry().id()).isEqualTo("chk_0123456789ABCDEF");
            assertThat(second.ledger().size()).isEqualTo(1);
        }

        @Test
        void scopesTheKeyToOneHabit() {
            AppendResult first =
                    Ledger.empty().append(draft().key("watch-2025-01-10").build());
            AppendResult second = first.ledger()
                    .append(draft().id("chk_0000000000000009")
                            .habit(STRETCHES)
                            .key("watch-2025-01-10")
                            .build());
            assertThat(second.accepted()).isTrue();
            assertThat(second.ledger().size()).isEqualTo(2);
        }

        @Test
        void findsAnEntryByItsKey() {
            Ledger ledger = Ledger.empty()
                    .append(draft().key("watch-2025-01-10").build())
                    .ledger();
            assertThat(ledger.findByKey(TREADMILL, "watch-2025-01-10").map(CheckIn::id))
                    .hasValue("chk_0123456789ABCDEF");
            assertThat(ledger.findByKey(TREADMILL, "nothing-here")).isEmpty();
        }
    }

    @Nested
    class VoidingAndAmending {

        private final Ledger ledger =
                Ledger.empty().append(draft().reading(20).build()).ledger();
        private final Instant when = at("2025-01-11T09:00:00Z");

        @Test
        void keepsAVoidedEntryButDropsItFromTheLiveList() {
            Ledger voided = ledger.voidEntry(Ids.checkInId("chk_0123456789ABCDEF"), when);
            assertThat(voided.size()).isEqualTo(1);
            assertThat(voided.liveEntries()).isEmpty();
        }

        @Test
        void amendsAReadingInPlace() {
            Ledger amended = ledger.amendEntry(Ids.checkInId("chk_0123456789ABCDEF"), OptionalDouble.of(25));
            assertThat(amended.liveEntries().get(0).reading()).hasValue(25);
        }

        @Test
        void complainsAboutAnIdItDoesNotHold() {
            assertThatThrownBy(() -> ledger.voidEntry(Ids.checkInId("chk_9999999999999999"), when))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void leavesTheOriginalLedgerAlone() {
            ledger.voidEntry(Ids.checkInId("chk_0123456789ABCDEF"), when);
            assertThat(ledger.liveEntries()).hasSize(1);
        }
    }

    @Nested
    class ReadingBack {

        private final Ledger ledger = Ledger.empty()
                .appendAll(List.of(
                        draft().id("chk_0000000000000001")
                                .at("2025-01-10T06:00:00Z")
                                .build(),
                        draft().id("chk_0000000000000002")
                                .at("2025-01-10T18:00:00Z")
                                .habit(STRETCHES)
                                .build(),
                        draft().id("chk_0000000000000003")
                                .at("2025-01-12T06:00:00Z")
                                .build()));

        @Test
        void filtersToOneHabit() {
            assertThat(ids(ledger.entriesForHabit(TREADMILL)))
                    .containsExactly("chk_0000000000000001", "chk_0000000000000003");
        }

        @Test
        void listsTheHabitsItHasHeardAbout() {
            assertThat(ledger.habitsTouched())
                    .isEqualTo(Stream.of(TREADMILL, STRETCHES).sorted().toList());
        }

        @Test
        void takesAHalfOpenInterval() {
            List<CheckIn> found = ledger.entriesBetween(at("2025-01-10T06:00:00Z"), at("2025-01-12T06:00:00Z"));
            assertThat(ids(found)).containsExactly("chk_0000000000000001", "chk_0000000000000002");
        }

        @Test
        void leavesVoidedEntriesOutOfARangeRead() {
            Ledger voided = ledger.voidEntry(Ids.checkInId("chk_0000000000000001"), at("2025-01-13T09:00:00Z"));
            List<CheckIn> found = voided.entriesBetween(at("2025-01-10T00:00:00Z"), at("2025-01-11T00:00:00Z"));
            assertThat(ids(found)).containsExactly("chk_0000000000000002");
        }
    }

    @Nested
    class TheIndex {

        @Test
        void findsAnEntryByIdWithoutWalkingTheList() {
            Ledger ledger = Ledger.empty()
                    .appendAll(List.of(
                            draft().id("chk_0000000000000001")
                                    .at("2025-01-10T06:00:00Z")
                                    .build(),
                            draft().id("chk_0000000000000002")
                                    .at("2025-01-10T18:00:00Z")
                                    .build()));
            assertThat(ledger.byId()).hasSize(2);
            assertThat(ledger.byId().get("chk_0000000000000002").id()).isEqualTo("chk_0000000000000002");
        }

        @Test
        void holdsTheFirstEntryForEachIdempotencyKey() {
            Ledger ledger = Ledger.empty()
                    .append(draft().key("watch-2025-01-10").build())
                    .ledger();
            assertThat(ledger.byKey().get(TREADMILL + ":watch-2025-01-10").id()).isEqualTo("chk_0123456789ABCDEF");
        }

        @Test
        void keepsVoidedEntriesOutOfThePerHabitIndex() {
            Ledger ledger = Ledger.empty()
                    .append(draft().id("chk_0000000000000001").build())
                    .ledger();
            assertThat(ledger.liveByHabit().get(TREADMILL)).hasSize(1);
            Ledger voided = ledger.voidEntry(Ids.checkInId("chk_0000000000000001"), at("2025-01-11T09:00:00Z"));
            assertThat(voided.liveByHabit()).doesNotContainKey(TREADMILL);
            assertThat(voided.byId()).hasSize(1);
        }

        @Test
        void rebuildsTheIndexOnAnAmendment() {
            Ledger ledger = Ledger.empty()
                    .append(draft().id("chk_0000000000000001").reading(20).build())
                    .ledger();
            Ledger amended = ledger.amendEntry(Ids.checkInId("chk_0000000000000001"), OptionalDouble.of(25));
            assertThat(amended.byId().get("chk_0000000000000001").reading()).hasValue(25);
            assertThat(amended.liveByHabit().get(TREADMILL).get(0).reading()).hasValue(25);
        }

        @Test
        void isEmptyForAnEmptyLedger() {
            Ledger ledger = Ledger.empty();
            assertThat(ledger.byId()).isEmpty();
            assertThat(ledger.byKey()).isEmpty();
            assertThat(ledger.liveByHabit()).isEmpty();
        }
    }
}
