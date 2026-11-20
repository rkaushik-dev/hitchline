package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventBusTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final Instant AT = Zones.parseInstant("2025-05-13T09:00:00Z");

    private static DomainEvent anEvent() {
        return DomainEvent.of(EventName.HABIT_CREATED, AT, ADA).withHabit(TREADMILL);
    }

    @Nested
    class BuildingAnEvent {

        @Test
        void keepsTheSubjectIdsItWasGiven() {
            DomainEvent event = anEvent();
            assertThat(event.habitId()).hasValue(TREADMILL);
            assertThat(event.checkInId()).isEmpty();
            assertThat(event.detail()).isEmpty();
        }

        @Test
        void trimsAndBoundsTheDetail() {
            assertThat(anEvent().withDetail("  cadence changed  ").detail()).isEqualTo("cadence changed");
            assertThatThrownBy(() -> anEvent().withDetail("x".repeat(241))).isInstanceOf(ValidationException.class);
        }

        @Test
        void readsBackInOneLine() {
            assertThat(anEvent().withDetail("cadence changed").describe())
                    .isEqualTo("2025-05-13T09:00:00.000Z habit.created hab_0123456789ABCDEF cadence changed");
        }

        @Test
        void fallsBackToTheMemberWhenNothingElseNamesIt() {
            assertThat(DomainEvent.of(EventName.CREW_ENROLLED, AT, ADA).describe())
                    .contains(ADA);
        }
    }

    @Nested
    class TheBus {

        @Test
        void callsASubscriberForItsEvent() {
            EventBus bus = EventBus.create();
            List<DomainEvent> seen = new ArrayList<>();
            bus.on(EventName.HABIT_CREATED, seen::add);
            bus.publish(anEvent());
            assertThat(seen).hasSize(1);
        }

        @Test
        void doesNotCallItForAnotherEvent() {
            EventBus bus = EventBus.create();
            AtomicInteger heard = new AtomicInteger();
            bus.on(EventName.CHECKIN_RECORDED, event -> heard.incrementAndGet());
            bus.publish(anEvent());
            assertThat(heard).hasValue(0);
        }

        @Test
        void callsAWildcardSubscriberForEverything() {
            EventBus bus = EventBus.create();
            AtomicInteger heard = new AtomicInteger();
            bus.onEvery(event -> heard.incrementAndGet());
            bus.publish(anEvent());
            bus.publish(DomainEvent.of(EventName.CHECKIN_RECORDED, AT, ADA)
                    .withCheckIn(Ids.checkInId("chk_0123456789ABCDEF")));
            assertThat(heard).hasValue(2);
        }

        @Test
        void hasCalledEverySubscriberByTheTimePublishReturns() {
            EventBus bus = EventBus.create();
            AtomicBoolean done = new AtomicBoolean();
            bus.on(EventName.HABIT_CREATED, event -> done.set(true));
            bus.publish(anEvent());
            assertThat(done).isTrue();
        }

        @Test
        void stopsCallingOneThatUnsubscribed() {
            EventBus bus = EventBus.create();
            AtomicInteger heard = new AtomicInteger();
            Runnable off = bus.on(EventName.HABIT_CREATED, event -> heard.incrementAndGet());
            off.run();
            bus.publish(anEvent());
            assertThat(heard).hasValue(0);
            assertThat(bus.listeners()).isZero();
        }

        @Test
        void carriesOnPastASubscriberThatThrows() {
            List<RuntimeException> failures = new ArrayList<>();
            EventBus bus = EventBus.create((failure, event) -> failures.add(failure));
            AtomicInteger later = new AtomicInteger();
            bus.on(EventName.HABIT_CREATED, event -> {
                throw new IllegalStateException("audit is down");
            });
            bus.on(EventName.HABIT_CREATED, event -> later.incrementAndGet());
            bus.publish(anEvent());
            assertThat(later).hasValue(1);
            assertThat(failures).hasSize(1);
        }

        @Test
        void countsWhatIsListening() {
            EventBus bus = EventBus.create();
            bus.on(EventName.HABIT_CREATED, event -> {});
            bus.onEvery(event -> {});
            assertThat(bus.listeners()).isEqualTo(2);
        }

        @Test
        void publishesToNobodyWithoutComplaint() {
            assertThatCode(() -> EventBus.create().publish(anEvent())).doesNotThrowAnyException();
        }
    }
}
