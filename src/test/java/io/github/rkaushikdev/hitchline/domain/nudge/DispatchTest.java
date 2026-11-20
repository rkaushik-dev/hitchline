package io.github.rkaushikdev.hitchline.domain.nudge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DispatchTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final PlannedNudge NUDGE = new PlannedNudge(
            TREADMILL,
            "hab_0123456789ABCDEF:day:2025-06-02",
            at("2025-06-02T18:00:00Z"),
            CivilDate.parse("2025-06-02"),
            NudgeReason.NOTHING_YET);
    private static final Dispatch PLANNED = Dispatch.plan(NUDGE, ADA, NudgeChannel.PUSH, at("2025-06-02T09:00:00Z"));

    private static Instant at(String text) {
        return Zones.parseInstant(text);
    }

    @Nested
    class PlanningOne {

        @Test
        void startsUnsentWithNoAttempts() {
            assertThat(PLANNED.state()).isEqualTo(DispatchState.PLANNED);
            assertThat(PLANNED.attempts()).isZero();
            assertThat(PLANNED.key()).isEqualTo(NUDGE.key());
            assertThat(PLANNED.channel()).isEqualTo(NudgeChannel.PUSH);
        }
    }

    @Nested
    class MovingThroughTheStates {

        @Test
        void countsAnAttemptWhenItIsSent() {
            Dispatch sent = PLANNED.markSent(at("2025-06-02T18:00:00Z"));
            assertThat(sent.state()).isEqualTo(DispatchState.SENT);
            assertThat(sent.attempts()).isEqualTo(1);
        }

        @Test
        void countsASecondAttempt() {
            Dispatch twice = PLANNED.markSent(at("2025-06-02T18:00:00Z")).markSent(at("2025-06-02T19:00:00Z"));
            assertThat(twice.attempts()).isEqualTo(2);
        }

        @Test
        void settlesOnceSomebodyLooksAtIt() {
            Dispatch seen = PLANNED.markSent(at("2025-06-02T18:00:00Z")).markSeen(at("2025-06-02T18:05:00Z"));
            assertThat(seen.isSettled()).isTrue();
            assertThat(seen.attempts()).isEqualTo(1);
        }

        @Test
        void refusesToMoveOnceItIsSettled() {
            Dispatch seen = PLANNED.markSent(at("2025-06-02T18:00:00Z")).markSeen(at("2025-06-02T18:05:00Z"));
            assertThatThrownBy(() -> seen.markSent(at("2025-06-02T19:00:00Z"))).isInstanceOf(ConflictException.class);
        }

        @Test
        void refusesAMoveBackwardsInTime() {
            assertThatThrownBy(() -> PLANNED.markSent(at("2025-06-01T09:00:00Z")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void needsAReasonToDropOne() {
            assertThatThrownBy(() -> PLANNED.markDropped(at("2025-06-02T18:00:00Z"), "  "))
                    .hasMessageContaining("is needed when a nudge is dropped");
            Dispatch dropped = PLANNED.markDropped(at("2025-06-02T18:00:00Z"), "quiet hours");
            assertThat(dropped.note()).isEqualTo("quiet hours");
            assertThat(dropped.isSettled()).isTrue();
        }
    }

    @Nested
    class Retries {

        private final Dispatch sent = PLANNED.markSent(at("2025-06-02T18:00:00Z"));

        @Test
        void waitsForTheBackoff() {
            assertThat(sent.shouldRetry(at("2025-06-02T18:20:00Z"))).isFalse();
            assertThat(sent.shouldRetry(at("2025-06-02T18:31:00Z"))).isTrue();
        }

        @Test
        void givesUpAfterTheAttemptsRunOut() {
            Dispatch entry = sent;
            for (int attempt = 1; attempt < RetryPolicy.DEFAULT.maxAttempts(); attempt += 1) {
                entry = entry.markSent(at("2025-06-03T18:00:00Z"));
            }
            assertThat(entry.attempts()).isEqualTo(RetryPolicy.DEFAULT.maxAttempts());
            assertThat(entry.shouldRetry(at("2025-06-04T18:00:00Z"))).isFalse();
        }

        @Test
        void neverRetriesSomethingThatWasSeenOrNeverSent() {
            assertThat(PLANNED.shouldRetry(at("2025-06-03T18:00:00Z"))).isFalse();
            Dispatch seen = sent.markSeen(at("2025-06-02T18:05:00Z"));
            assertThat(seen.shouldRetry(at("2025-06-03T18:00:00Z"))).isFalse();
        }

        @Test
        void takesATighterPolicy() {
            assertThat(sent.shouldRetry(at("2025-06-02T18:06:00Z"), new RetryPolicy(5, 5)))
                    .isTrue();
        }
    }

    @Nested
    class WhatIsDue {

        @Test
        void picksThePlannedOnesWhoseMomentHasCome() {
            PlannedNudge laterNudge = new PlannedNudge(
                    NUDGE.habitId(), "later", at("2025-06-03T18:00:00Z"), NUDGE.onDate(), NUDGE.reason());
            Dispatch later = Dispatch.plan(laterNudge, ADA, NudgeChannel.PUSH, at("2025-06-02T09:00:00Z"));
            assertThat(Dispatch.dueNow(List.of(PLANNED, later), at("2025-06-02T18:30:00Z")).stream()
                            .map(Dispatch::key))
                    .containsExactly(NUDGE.key());
        }

        @Test
        void leavesOutAnythingAlreadySent() {
            Dispatch sent = PLANNED.markSent(at("2025-06-02T18:00:00Z"));
            assertThat(Dispatch.dueNow(List.of(sent), at("2025-06-02T18:30:00Z")))
                    .isEmpty();
        }
    }

    @Nested
    class DescribingOne {

        @Test
        void readsBackWithItsStateAndNote() {
            Dispatch dropped = PLANNED.markDropped(at("2025-06-02T18:00:00Z"), "quiet hours");
            assertThat(dropped.describe())
                    .isEqualTo("hab_0123456789ABCDEF:day:2025-06-02 dropped by push after 0 (quiet hours)");
        }
    }
}
