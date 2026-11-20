package io.github.rkaushikdev.hitchline.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An in process bus. Subscribers to one event are called in the order they subscribed, then the
 * subscribers to every event. A subscriber that throws is reported and the rest still run: an
 * audit line failing to write must not undo a check in that already landed in the journal.
 */
public final class EventBus {

    private static final String EVERY = "*";

    private final Map<String, CopyOnWriteArraySet<Consumer<DomainEvent>>> byName = new ConcurrentHashMap<>();
    private final Optional<BiConsumer<RuntimeException, DomainEvent>> onError;

    private EventBus(Optional<BiConsumer<RuntimeException, DomainEvent>> onError) {
        this.onError = onError;
    }

    /** A bus that drops a subscriber's failure silently. */
    public static EventBus create() {
        return new EventBus(Optional.empty());
    }

    /** A bus that hands each subscriber failure to {@code onError} with the event it was handling. */
    public static EventBus create(BiConsumer<RuntimeException, DomainEvent> onError) {
        return new EventBus(Optional.of(onError));
    }

    private Runnable subscribe(String key, Consumer<DomainEvent> subscriber) {
        CopyOnWriteArraySet<Consumer<DomainEvent>> held =
                byName.computeIfAbsent(key, name -> new CopyOnWriteArraySet<>());
        held.add(subscriber);
        return () -> held.remove(subscriber);
    }

    /** Subscribes to one event, answering the way to unsubscribe. */
    public Runnable on(EventName name, Consumer<DomainEvent> subscriber) {
        return subscribe(name.wire(), subscriber);
    }

    /** Subscribes to every event, answering the way to unsubscribe. */
    public Runnable onEvery(Consumer<DomainEvent> subscriber) {
        return subscribe(EVERY, subscriber);
    }

    /** Calls every subscriber for the event before returning. */
    public void publish(DomainEvent event) {
        List<Consumer<DomainEvent>> wanted = new ArrayList<>();
        wanted.addAll(byName.getOrDefault(event.name().wire(), new CopyOnWriteArraySet<>()));
        wanted.addAll(byName.getOrDefault(EVERY, new CopyOnWriteArraySet<>()));
        for (Consumer<DomainEvent> subscriber : wanted) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException failure) {
                onError.ifPresent(report -> report.accept(failure, event));
            }
        }
    }

    /** How many subscriptions are live. */
    public int listeners() {
        return byName.values().stream().mapToInt(CopyOnWriteArraySet::size).sum();
    }
}
