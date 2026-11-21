package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.SyncService.QueuedCheckIn;
import io.github.rkaushikdev.hitchline.app.SyncService.SyncItemResult;
import io.github.rkaushikdev.hitchline.app.SyncService.SyncOutcome;
import io.github.rkaushikdev.hitchline.app.SyncService.SyncReport;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SyncServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private MemoryStore store;
    private SyncService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
        service = new SyncService(store);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/day").startsOn("2025-01-06")));
    }

    private static QueuedCheckIn queued(String localId, String at) {
        return QueuedCheckIn.of(localId, TREADMILL, at);
    }

    private static QueuedCheckIn queued(String localId, String at, String idempotencyKey) {
        return new QueuedCheckIn(
                localId, TREADMILL, at, OptionalDouble.empty(), Optional.empty(), Optional.of(idempotencyKey));
    }

    private static SyncItemResult resultFor(SyncReport report, String localId) {
        return report.results().stream()
                .filter(result -> result.localId().equals(localId))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class AHandsetComingBackIntoSignal {

        @Test
        void storesEverythingItQueued() {
            SyncReport report = service.push(
                    ADA, List.of(queued("local-1", "2025-01-18T07:00:00Z"), queued("local-2", "2025-01-19T07:00:00Z")));
            assertThat(report.accepted()).isEqualTo(2);
            assertThat(report.results())
                    .allSatisfy(result -> assertThat(result.outcome()).isEqualTo(SyncOutcome.ACCEPTED));
            assertThat(store.checkIns().byHabit(TREADMILL)).hasSize(2);
        }

        @Test
        void answersPerItemSoTheHandsetKnowsWhatToClear() {
            SyncReport report = service.push(ADA, List.of(queued("local-1", "2025-01-18T07:00:00Z")));
            assertThat(report.results().get(0).localId()).isEqualTo("local-1");
            assertThat(report.results().get(0).checkInId()).isPresent();
        }

        @Test
        void sortsTheBatchByWhenThingsHappened() {
            SyncReport report = service.push(
                    ADA, List.of(queued("local-2", "2025-01-19T07:00:00Z"), queued("local-1", "2025-01-18T07:00:00Z")));
            assertThat(report.results().stream().map(SyncItemResult::localId)).containsExactly("local-1", "local-2");
        }

        @Test
        void doesNotDoubleCountABatchSentTwice() {
            List<QueuedCheckIn> batch = List.of(queued("local-1", "2025-01-18T07:00:00Z"));
            service.push(ADA, batch);
            SyncReport second = service.push(ADA, batch);
            assertThat(second.duplicates()).isEqualTo(1);
            assertThat(second.results().get(0).outcome()).isEqualTo(SyncOutcome.ALREADY_HAD_IT);
            assertThat(second.results().get(0).outcome().wire()).isEqualTo("already_had_it");
            assertThat(store.checkIns().byHabit(TREADMILL)).hasSize(1);
        }

        @Test
        void honoursAKeyTheHandsetChoseItself() {
            service.push(ADA, List.of(queued("local-1", "2025-01-18T07:00:00Z", "watch-0118")));
            SyncReport again = service.push(ADA, List.of(queued("local-9", "2025-01-18T07:00:00Z", "watch-0118")));
            assertThat(again.duplicates()).isEqualTo(1);
        }

        @Test
        void keepsTheGoodRowsWhenOneIsBad() {
            SyncReport report = service.push(
                    ADA,
                    List.of(
                            queued("local-1", "2025-01-18T07:00:00Z"),
                            queued("local-2", "not a time"),
                            queued("local-3", "2025-01-19T07:00:00Z")));
            assertThat(report.accepted()).isEqualTo(2);
            assertThat(report.refused()).isEqualTo(1);
            assertThat(resultFor(report, "local-2").message()).contains("iso timestamp");
        }

        @Test
        void refusesARowForAHabitThatHasGone() {
            SyncReport report = service.push(
                    ADA, List.of(QueuedCheckIn.of("local-1", "hab_9999999999999999", "2025-01-18T07:00:00Z")));
            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.results().get(0).outcome()).isEqualTo(SyncOutcome.REFUSED);
        }

        @Test
        void refusesARowDatedInTheFutureRatherThanStoringIt() {
            SyncReport report = service.push(ADA, List.of(queued("local-1", "2025-02-01T07:00:00Z")));
            assertThat(report.refused()).isEqualTo(1);
        }

        @Test
        void capsAnAbsurdBatchRatherThanWorkingThroughIt() {
            List<QueuedCheckIn> batch = IntStream.range(0, 600)
                    .mapToObj(index -> queued("local-" + index, "2025-01-18T07:00:00Z", "queued-" + index))
                    .toList();
            assertThat(service.push(ADA, batch).results()).hasSize(500);
        }

        @Test
        void readsAnEmptyBatchAsNothingToDo() {
            SyncReport report = service.push(ADA, List.of());
            assertThat(report.accepted()).isZero();
            assertThat(report.duplicates()).isZero();
            assertThat(report.refused()).isZero();
        }
    }

    @Nested
    class WhatTheHandsetMayClear {

        @Test
        void isEverythingThatWillNotChangeItsAnswer() {
            SyncReport report = service.push(
                    ADA, List.of(queued("local-1", "2025-01-18T07:00:00Z"), queued("local-2", "not a time")));
            assertThat(report.settledIds()).containsExactly("local-1");
        }

        @Test
        void readsBackInOneLine() {
            SyncReport report = service.push(ADA, List.of(queued("local-1", "2025-01-18T07:00:00Z")));
            assertThat(report.describe()).isEqualTo("1 stored, 0 already here, 0 refused");
        }
    }
}
