package io.github.rkaushikdev.hitchline.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShutdownTest {

    @Nested
    class RegisteringTasks {

        @Test
        void countsWhatItHolds() {
            Shutdown shutdown = Shutdown.create();
            shutdown.add("http", () -> {});
            shutdown.add("store", () -> {});
            assertThat(shutdown.size()).isEqualTo(2);
            assertThat(shutdown.started()).isFalse();
        }

        @Test
        void refusesAnEmptyName() {
            assertThatThrownBy(() -> Shutdown.create().add("  ", () -> {})).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesATaskAddedOnceItHasStarted() {
            Shutdown shutdown = Shutdown.create();
            shutdown.run();
            assertThatThrownBy(() -> shutdown.add("late", () -> {})).hasMessageContaining("while shutting down");
        }
    }

    @Nested
    class Running {

        @Test
        void runsTheTasksInOrder() {
            List<String> order = new ArrayList<>();
            Shutdown shutdown = Shutdown.create();
            shutdown.add("first", () -> order.add("first"));
            shutdown.add("second", () -> order.add("second"));
            ShutdownReport report = shutdown.run();
            assertThat(order).containsExactly("first", "second");
            assertThat(report).isEqualTo(new ShutdownReport(2, List.of()));
        }

        @Test
        void carriesOnPastATaskThatThrows() {
            AtomicInteger later = new AtomicInteger();
            Shutdown shutdown = Shutdown.create();
            shutdown.add("broken", () -> {
                throw new IllegalStateException("no");
            });
            shutdown.add("store", later::incrementAndGet);
            ShutdownReport report = shutdown.run();
            assertThat(report.failed()).containsExactly("broken");
            assertThat(later).hasValue(1);
        }

        @Test
        void namesATaskThatFailsWithACheckedExceptionAsFailed() {
            Shutdown shutdown = Shutdown.create();
            shutdown.add("flush", () -> {
                throw new IOException("no");
            });
            assertThat(shutdown.run().failed()).containsExactly("flush");
        }

        @Test
        void runsOnceHoweverManyTimesItIsCalled() {
            AtomicInteger task = new AtomicInteger();
            Shutdown shutdown = Shutdown.create();
            shutdown.add("once", task::incrementAndGet);
            CompletableFuture.allOf(
                            CompletableFuture.runAsync(shutdown::run),
                            CompletableFuture.runAsync(shutdown::run),
                            CompletableFuture.runAsync(shutdown::run))
                    .join();
            assertThat(task).hasValue(1);
        }

        @Test
        void runsNothingWhenNothingWasRegistered() {
            assertThat(Shutdown.create().run()).isEqualTo(new ShutdownReport(0, List.of()));
        }
    }

    @Nested
    class Signals {

        @Test
        void wiresBothOfThem() {
            Map<SignalName, Runnable> listeners = new EnumMap<>(SignalName.class);
            SignalTarget target = listeners::put;
            AtomicInteger task = new AtomicInteger();
            Shutdown shutdown = Shutdown.create();
            shutdown.add("store", task::incrementAndGet);

            AtomicReference<ShutdownReport> seen = new AtomicReference<>();
            int wired = Shutdown.onSignals(target, shutdown, seen::set);
            assertThat(wired).isEqualTo(2);
            assertThat(listeners.keySet()).containsExactly(SignalName.SIGINT, SignalName.SIGTERM);

            listeners.get(SignalName.SIGTERM).run();
            shutdown.run();
            assertThat(task).hasValue(1);
            assertThat(seen.get().ran()).isEqualTo(1);
        }
    }
}
