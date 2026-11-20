package io.github.rkaushikdev.hitchline.domain.checkin;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.UnaryOperator;

/**
 * The record of what was done, in event order. Nothing is ever removed: an entry that turns out to
 * be wrong is voided and stays in place, because a supervisor asking why an adherence figure moved
 * needs to see the entry that moved it. A ledger never changes; every change hands back a new one.
 *
 * <p>Lookups go through an index built when the ledger is made. Building it costs a pass over the
 * entries, which the sort already costs, and it takes the id and idempotency lookups off a linear
 * scan. A hitch of check ins for a crew of forty is a few thousand rows, and the import path asks
 * for a key on every one of them.
 */
public final class Ledger {

    private final List<CheckIn> entries;
    private final Map<String, CheckIn> byId = new LinkedHashMap<>();
    private final Map<String, CheckIn> byKey = new LinkedHashMap<>();
    private final Map<String, List<CheckIn>> liveByHabit = new LinkedHashMap<>();

    private Ledger(List<CheckIn> entries) {
        this.entries = List.copyOf(entries);
        for (CheckIn entry : this.entries) {
            byId.put(entry.id(), entry);
            keyOf(entry).ifPresent(key -> byKey.putIfAbsent(key, entry));
            if (!entry.isVoided()) {
                liveByHabit
                        .computeIfAbsent(entry.habitId(), habit -> new ArrayList<>())
                        .add(entry);
            }
        }
        liveByHabit.replaceAll((habit, held) -> List.copyOf(held));
    }

    private static Optional<String> keyOf(CheckIn entry) {
        return entry.idempotencyKey().map(key -> entry.habitId() + ":" + key);
    }

    public static Ledger empty() {
        return new Ledger(List.of());
    }

    /** A ledger of {@code entries}, put into event order. */
    public static Ledger of(List<CheckIn> entries) {
        List<CheckIn> sorted = new ArrayList<>(entries);
        sorted.sort(CheckIn.ORDER);
        return new Ledger(sorted);
    }

    /** Every entry, voided ones included, in event order. */
    public List<CheckIn> entries() {
        return entries;
    }

    /** Every entry by id. */
    public Map<String, CheckIn> byId() {
        return Collections.unmodifiableMap(byId);
    }

    /** The first entry for each {@code habitId:idempotencyKey}. */
    public Map<String, CheckIn> byKey() {
        return Collections.unmodifiableMap(byKey);
    }

    /** The live entries of each habit that has any, in event order. */
    public Map<String, List<CheckIn>> liveByHabit() {
        return Collections.unmodifiableMap(liveByHabit);
    }

    public int size() {
        return entries.size();
    }

    public List<CheckIn> liveEntries() {
        return entries.stream().filter(entry -> !entry.isVoided()).toList();
    }

    public Optional<CheckIn> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The first entry for {@code habitId} that carried idempotency key {@code key}. */
    public Optional<CheckIn> findByKey(String habitId, String key) {
        return Optional.ofNullable(byKey.get(habitId + ":" + key));
    }

    /**
     * Adds an entry. An id already in the ledger is a conflict; an idempotency key already used for
     * the habit is a retry, answered with the entry that used it and the ledger unchanged.
     */
    public AppendResult append(CheckIn entry) {
        if (byId.containsKey(entry.id())) {
            throw new ConflictException("check in " + entry.id() + " is already in the ledger");
        }
        Optional<CheckIn> seen = keyOf(entry).map(byKey::get);
        if (seen.isPresent()) {
            return new AppendResult(this, false, seen.get());
        }
        List<CheckIn> next = new ArrayList<>(entries);
        next.add(entry);
        return new AppendResult(of(next), true, entry);
    }

    public Ledger appendAll(List<CheckIn> more) {
        Ledger current = this;
        for (CheckIn entry : more) {
            current = current.append(entry).ledger();
        }
        return current;
    }

    private Ledger replace(String id, UnaryOperator<CheckIn> change) {
        if (!byId.containsKey(id)) {
            throw new NotFoundException("check in", id);
        }
        return new Ledger(entries.stream()
                .map(entry -> entry.id().equals(id) ? change.apply(entry) : entry)
                .toList());
    }

    public Ledger voidEntry(String id, Instant at) {
        return replace(id, entry -> entry.voidAt(at));
    }

    public Ledger amendEntry(String id, OptionalDouble reading) {
        return replace(id, entry -> entry.amendReading(reading));
    }

    /** The live entries for one habit, in event order. */
    public List<CheckIn> entriesForHabit(String habitId) {
        return liveByHabit.getOrDefault(habitId, List.of());
    }

    /** Live entries whose event time falls in the half open interval, oldest first. */
    public List<CheckIn> entriesBetween(Instant from, Instant to) {
        return liveEntries().stream()
                .filter(entry -> !entry.at().isBefore(from) && entry.at().isBefore(to))
                .toList();
    }

    public Optional<CheckIn> lastEntry() {
        List<CheckIn> live = liveEntries();
        return live.isEmpty() ? Optional.empty() : Optional.of(live.get(live.size() - 1));
    }

    /** Every habit with a live entry, in id order. */
    public List<String> habitsTouched() {
        List<String> habits = new ArrayList<>(liveByHabit.keySet());
        Collections.sort(habits);
        return habits;
    }
}
