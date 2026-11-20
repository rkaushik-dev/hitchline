package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.CheckInRepository;
import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.app.CrewRepository;
import io.github.rkaushikdev.hitchline.app.Durable;
import io.github.rkaushikdev.hitchline.app.EventBus;
import io.github.rkaushikdev.hitchline.app.HabitRepository;
import io.github.rkaushikdev.hitchline.app.IdSource;
import io.github.rkaushikdev.hitchline.app.MemberRepository;
import io.github.rkaushikdev.hitchline.app.OverrideRepository;
import io.github.rkaushikdev.hitchline.app.PolicyRepository;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.app.SharingRepository;
import io.github.rkaushikdev.hitchline.app.ShieldRepository;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The store the service actually runs on. State is held in memory for reads and every change is
 * appended to the journal before it is acknowledged, so a crash loses nothing that was reported
 * as saved. A store that writes holds the writer lock beside the journal while it is open.
 */
public final class FileStore implements Ports, Durable {

    private final Path path;
    private final Clock clock;
    private final IdSource ids;
    private final Journal journal;
    private final Optional<LockHolder> held;

    private final Map<String, CrewMember> members = new LinkedHashMap<>();
    private final Map<String, Habit> habits = new LinkedHashMap<>();
    private final Map<String, CheckIn> checkIns = new LinkedHashMap<>();
    private final Map<String, Crew> crews = new LinkedHashMap<>();
    private final Map<String, Membership> memberships = new LinkedHashMap<>();
    private final Map<String, SharingPreference> sharing = new LinkedHashMap<>();
    private final Map<String, NudgePolicy> policies = new LinkedHashMap<>();
    private final List<RosterOverride> overrides = new ArrayList<>();
    private final List<Shield> shields = new ArrayList<>();

    private final MemberRepository memberRepository = new Members();
    private final HabitRepository habitRepository = new Habits();
    private final CheckInRepository checkInRepository = new CheckIns();
    private final OverrideRepository overrideRepository = new Overrides();
    private final ShieldRepository shieldRepository = new Shields();
    private final CrewRepository crewRepository = new Crews();
    private final SharingRepository sharingRepository = new Sharing();
    private final PolicyRepository policyRepository = new Policies();

    private FileStore(Path path, Clock clock, IdSource ids, boolean lock) {
        this.path = path;
        this.clock = clock;
        this.ids = ids;
        // The lock stamp is wall time, not the injected clock: staleness is about how long a
        // process has really been gone, and a test clock pinned to January would make every lock
        // look abandoned.
        this.held = lock
                ? Optional.of(Locks.takeLock(path, LockOwner.here(Zones.formatInstant(Instant.now()))))
                : Optional.empty();
        this.journal = Journal.open(path);
    }

    /** Opens the journal at {@code path} for writing, on the system clock with random ids. */
    public static FileStore open(Path path) {
        return open(path, Clocks.systemClock(), IdSources.randomIds(), true);
    }

    /**
     * Opens the journal at {@code path} and loads it. With {@code lock} the store takes the writer
     * lock; a tool that only reads passes false and shares the journal with a running server.
     */
    public static FileStore open(Path path, Clock clock, IdSource ids, boolean lock) {
        FileStore store = new FileStore(path, clock, ids, lock);
        try {
            store.reload();
        } catch (RuntimeException unloadable) {
            store.close();
            throw unloadable;
        }
        return store;
    }

    public Path path() {
        return path;
    }

    private static String idOf(JsonNode row) {
        return Codec.keyText(row, "id");
    }

    private static String memberKey(JsonNode row) {
        return Codec.keyText(row, "memberId");
    }

    private static String membershipKey(JsonNode row) {
        return Codec.keyText(row, "crewId") + "|" + Codec.keyText(row, "memberId");
    }

    /** Throws away the state in memory and replays the journal. */
    public synchronized void reload() {
        ReadReport report = journal.read();
        List<JournalEntry> entries = report.entries();
        members.clear();
        habits.clear();
        checkIns.clear();
        crews.clear();
        memberships.clear();
        sharing.clear();
        policies.clear();
        for (JsonNode row :
                Journal.fold(entries, RecordKind.MEMBER, FileStore::idOf).values()) {
            CrewMember member = Codec.decodeMember(row);
            members.put(member.id(), member);
        }
        for (JsonNode row :
                Journal.fold(entries, RecordKind.HABIT, FileStore::idOf).values()) {
            Habit entry = Codec.decodeHabit(row);
            habits.put(entry.id(), entry);
        }
        for (JsonNode row :
                Journal.fold(entries, RecordKind.CHECKIN, FileStore::idOf).values()) {
            CheckIn entry = Codec.decodeCheckIn(row);
            checkIns.put(entry.id(), entry);
        }
        for (JsonNode row :
                Journal.fold(entries, RecordKind.CREW, FileStore::idOf).values()) {
            Crew entry = Codec.decodeCrew(row);
            crews.put(entry.id(), entry);
        }
        Journal.fold(entries, RecordKind.MEMBERSHIP, FileStore::membershipKey)
                .forEach((key, row) -> memberships.put(key, Codec.decodeMembership(row)));
        for (JsonNode row :
                Journal.fold(entries, RecordKind.SHARING, FileStore::memberKey).values()) {
            SharingPreference preference = Codec.decodeSharing(row);
            sharing.put(preference.memberId(), preference);
        }
        for (JsonNode row :
                Journal.fold(entries, RecordKind.POLICY, FileStore::memberKey).values()) {
            Codec.StoredPolicy stored = Codec.decodePolicy(row);
            policies.put(stored.memberId(), stored.policy());
        }
        overrides.clear();
        Journal.collect(entries, RecordKind.OVERRIDE).forEach(row -> overrides.add(Codec.decodeOverride(row)));
        shields.clear();
        Journal.collect(entries, RecordKind.SHIELD).forEach(row -> shields.add(Codec.decodeShield(row)));
    }

    private void write(RecordKind kind, JsonNode row) {
        journal.append(kind, Operation.PUT, row, Zones.formatInstant(clock.now()));
    }

    /** The journal behind the store, for maintenance commands. */
    public Journal journal() {
        return journal;
    }

    @Override
    public void flush() {
        journal.flush();
    }

    /** Closes the journal and gives up the writer lock. */
    @Override
    public void close() {
        journal.close();
        held.ifPresent(LockHolder::release);
    }

    @Override
    public Optional<EventBus> events() {
        return Optional.empty();
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public IdSource ids() {
        return ids;
    }

    @Override
    public MemberRepository members() {
        return memberRepository;
    }

    @Override
    public HabitRepository habits() {
        return habitRepository;
    }

    @Override
    public CheckInRepository checkIns() {
        return checkInRepository;
    }

    @Override
    public OverrideRepository overrides() {
        return overrideRepository;
    }

    @Override
    public ShieldRepository shields() {
        return shieldRepository;
    }

    @Override
    public CrewRepository crews() {
        return crewRepository;
    }

    @Override
    public SharingRepository sharing() {
        return sharingRepository;
    }

    @Override
    public PolicyRepository policies() {
        return policyRepository;
    }

    private final class Members implements MemberRepository {
        @Override
        public Optional<CrewMember> byId(String id) {
            synchronized (FileStore.this) {
                return Optional.ofNullable(members.get(id));
            }
        }

        @Override
        public List<CrewMember> all() {
            synchronized (FileStore.this) {
                return List.copyOf(members.values());
            }
        }

        @Override
        public void save(CrewMember member) {
            synchronized (FileStore.this) {
                write(RecordKind.MEMBER, Codec.encodeMember(member));
                members.put(member.id(), member);
            }
        }
    }

    private final class Habits implements HabitRepository {
        @Override
        public Optional<Habit> byId(String id) {
            synchronized (FileStore.this) {
                return Optional.ofNullable(habits.get(id));
            }
        }

        @Override
        public List<Habit> byMember(String memberId) {
            synchronized (FileStore.this) {
                return habits.values().stream()
                        .filter(entry -> entry.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public void save(Habit habit) {
            synchronized (FileStore.this) {
                write(RecordKind.HABIT, Codec.encodeHabit(habit));
                habits.put(habit.id(), habit);
            }
        }
    }

    private final class CheckIns implements CheckInRepository {
        @Override
        public Optional<CheckIn> byId(String id) {
            synchronized (FileStore.this) {
                return Optional.ofNullable(checkIns.get(id));
            }
        }

        @Override
        public List<CheckIn> byMember(String memberId) {
            synchronized (FileStore.this) {
                return checkIns.values().stream()
                        .filter(entry -> entry.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public List<CheckIn> byMember(String memberId, DateRange window) {
            synchronized (FileStore.this) {
                CrewMember member = members.get(memberId);
                String zone = member == null ? "UTC" : member.siteZone();
                return byMember(memberId).stream()
                        .filter(entry -> window.contains(Zones.civilDateIn(zone, entry.at())))
                        .toList();
            }
        }

        @Override
        public List<CheckIn> byHabit(String habitId) {
            synchronized (FileStore.this) {
                return checkIns.values().stream()
                        .filter(entry -> entry.habitId().equals(habitId))
                        .toList();
            }
        }

        @Override
        public void add(CheckIn entry) {
            synchronized (FileStore.this) {
                if (checkIns.containsKey(entry.id())) {
                    throw new ConflictException("check in " + entry.id() + " is already stored");
                }
                write(RecordKind.CHECKIN, Codec.encodeCheckIn(entry));
                checkIns.put(entry.id(), entry);
            }
        }

        @Override
        public void replace(CheckIn entry) {
            synchronized (FileStore.this) {
                if (!checkIns.containsKey(entry.id())) {
                    throw new NotFoundException("check in", entry.id());
                }
                write(RecordKind.CHECKIN, Codec.encodeCheckIn(entry));
                checkIns.put(entry.id(), entry);
            }
        }
    }

    private final class Overrides implements OverrideRepository {
        @Override
        public List<RosterOverride> byMember(String memberId) {
            synchronized (FileStore.this) {
                return overrides.stream()
                        .filter(entry -> entry.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public void add(RosterOverride entry) {
            synchronized (FileStore.this) {
                write(RecordKind.OVERRIDE, Codec.encodeOverride(entry));
                overrides.add(entry);
            }
        }
    }

    private final class Shields implements ShieldRepository {
        @Override
        public List<Shield> byMember(String memberId) {
            synchronized (FileStore.this) {
                return shields.stream()
                        .filter(entry -> entry.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public void add(Shield entry) {
            synchronized (FileStore.this) {
                write(RecordKind.SHIELD, Codec.encodeShield(entry));
                shields.add(entry);
            }
        }
    }

    private final class Crews implements CrewRepository {
        @Override
        public Optional<Crew> byId(String id) {
            synchronized (FileStore.this) {
                return Optional.ofNullable(crews.get(id));
            }
        }

        @Override
        public List<Crew> all() {
            synchronized (FileStore.this) {
                return List.copyOf(crews.values());
            }
        }

        @Override
        public void save(Crew crew) {
            synchronized (FileStore.this) {
                write(RecordKind.CREW, Codec.encodeCrew(crew));
                crews.put(crew.id(), crew);
            }
        }

        @Override
        public List<Membership> memberships() {
            synchronized (FileStore.this) {
                return List.copyOf(memberships.values());
            }
        }

        @Override
        public void addMembership(Membership entry) {
            synchronized (FileStore.this) {
                write(RecordKind.MEMBERSHIP, Codec.encodeMembership(entry));
                memberships.put(entry.crewId() + "|" + entry.memberId(), entry);
            }
        }

        @Override
        public void replaceMembership(Membership entry) {
            synchronized (FileStore.this) {
                String key = entry.crewId() + "|" + entry.memberId();
                if (!memberships.containsKey(key)) {
                    throw new NotFoundException("membership", entry.memberId());
                }
                write(RecordKind.MEMBERSHIP, Codec.encodeMembership(entry));
                memberships.put(key, entry);
            }
        }
    }

    private final class Sharing implements SharingRepository {
        @Override
        public Optional<SharingPreference> byMember(String memberId) {
            synchronized (FileStore.this) {
                return Optional.ofNullable(sharing.get(memberId));
            }
        }

        @Override
        public void save(SharingPreference preference) {
            synchronized (FileStore.this) {
                write(RecordKind.SHARING, Codec.encodeSharing(preference));
                sharing.put(preference.memberId(), preference);
            }
        }
    }

    private final class Policies implements PolicyRepository {
        @Override
        public Optional<NudgePolicy> byMember(String memberId) {
            synchronized (FileStore.this) {
                return Optional.ofNullable(policies.get(memberId));
            }
        }

        @Override
        public void save(String memberId, NudgePolicy policy) {
            synchronized (FileStore.this) {
                write(RecordKind.POLICY, Codec.encodePolicy(memberId, policy));
                policies.put(memberId, policy);
            }
        }
    }
}
