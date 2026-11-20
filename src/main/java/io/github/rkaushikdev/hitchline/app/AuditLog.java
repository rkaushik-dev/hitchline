package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * The audit trail. An operator being asked why an adherence figure moved needs to see what was
 * done and when, and the check in ledger only holds half of that: it says nothing about a roster
 * held over or a habit paused. The log keeps the newest entries, ten thousand by default.
 */
public final class AuditLog {

    static final int DEFAULT_KEEP = 10_000;

    private final int keep;
    private final Deque<AuditEntry> entries = new ArrayDeque<>();
    private long sequence;

    private AuditLog(int keep) {
        this.keep = keep;
    }

    public static AuditLog create() {
        return new AuditLog(DEFAULT_KEEP);
    }

    /** A log that drops its oldest entries once it holds more than {@code keep}. */
    public static AuditLog create(int keep) {
        return new AuditLog(keep);
    }

    /** Records an event as the next entry. */
    public synchronized AuditEntry record(DomainEvent event) {
        sequence += 1;
        AuditEntry entry = new AuditEntry(sequence, event);
        entries.addLast(entry);
        while (entries.size() > keep) {
            entries.removeFirst();
        }
        return entry;
    }

    /** Every entry held, oldest first. */
    public synchronized List<AuditEntry> all() {
        return List.copyOf(entries);
    }

    private synchronized List<AuditEntry> where(Predicate<AuditEntry> test) {
        return entries.stream().filter(test).toList();
    }

    public List<AuditEntry> forMember(String memberId) {
        return where(entry -> entry.memberId().equals(memberId));
    }

    /** The entries whose moment falls on a day of {@code window} as read in {@code zone}. */
    public List<AuditEntry> inWindow(DateRange window, String zone) {
        return where(entry -> window.contains(Zones.civilDateIn(zone, entry.at())));
    }

    public List<AuditEntry> named(EventName name) {
        return where(entry -> entry.name() == name);
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Records everything published on {@code bus}, answering the way to stop. */
    public static Runnable auditEverything(EventBus bus, AuditLog log) {
        return bus.onEvery(log::record);
    }
}
