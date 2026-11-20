package io.github.rkaushikdev.hitchline.infra.store;

import io.github.rkaushikdev.hitchline.app.CheckInRepository;
import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.app.CrewRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositories held in maps. This is the store the tests run against and the one a demo runs on,
 * and it is deliberately the same shape as the journal backed one so a service cannot tell them
 * apart. Every method locks the store, so it may be shared between threads.
 */
public final class MemoryStore implements Ports {

    private final Clock clock;
    private final IdSource ids;
    private final Map<String, CrewMember> members = new LinkedHashMap<>();
    private final Map<String, Habit> habits = new LinkedHashMap<>();
    private final Map<String, CheckIn> checkIns = new LinkedHashMap<>();
    private final List<RosterOverride> overrides = new ArrayList<>();
    private final List<Shield> shields = new ArrayList<>();
    private final Map<String, Crew> crews = new LinkedHashMap<>();
    private final List<Membership> memberships = new ArrayList<>();
    private final Map<String, SharingPreference> sharing = new LinkedHashMap<>();
    private final Map<String, NudgePolicy> policies = new LinkedHashMap<>();

    private final MemberRepository memberRepository = new Members();
    private final HabitRepository habitRepository = new Habits();
    private final CheckInRepository checkInRepository = new CheckIns();
    private final OverrideRepository overrideRepository = new Overrides();
    private final ShieldRepository shieldRepository = new Shields();
    private final CrewRepository crewRepository = new Crews();
    private final SharingRepository sharingRepository = new Sharing();
    private final PolicyRepository policyRepository = new Policies();

    private MemoryStore(Clock clock, IdSource ids) {
        this.clock = clock;
        this.ids = ids;
    }

    /** An empty store on the system clock with random ids. */
    public static MemoryStore create() {
        return new MemoryStore(Clocks.systemClock(), IdSources.randomIds());
    }

    public static MemoryStore create(Clock clock, IdSource ids) {
        return new MemoryStore(clock, ids);
    }

    /** Empties every table. */
    public synchronized void reset() {
        members.clear();
        habits.clear();
        checkIns.clear();
        overrides.clear();
        shields.clear();
        crews.clear();
        memberships.clear();
        sharing.clear();
        policies.clear();
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
            synchronized (MemoryStore.this) {
                return Optional.ofNullable(members.get(id));
            }
        }

        @Override
        public List<CrewMember> all() {
            synchronized (MemoryStore.this) {
                return List.copyOf(members.values());
            }
        }

        @Override
        public void save(CrewMember member) {
            synchronized (MemoryStore.this) {
                members.put(member.id(), member);
            }
        }
    }

    private final class Habits implements HabitRepository {
        @Override
        public Optional<Habit> byId(String id) {
            synchronized (MemoryStore.this) {
                return Optional.ofNullable(habits.get(id));
            }
        }

        @Override
        public List<Habit> byMember(String memberId) {
            synchronized (MemoryStore.this) {
                return habits.values().stream()
                        .filter(habit -> habit.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public void save(Habit habit) {
            synchronized (MemoryStore.this) {
                habits.put(habit.id(), habit);
            }
        }
    }

    private final class CheckIns implements CheckInRepository {
        @Override
        public Optional<CheckIn> byId(String id) {
            synchronized (MemoryStore.this) {
                return Optional.ofNullable(checkIns.get(id));
            }
        }

        @Override
        public List<CheckIn> byMember(String memberId) {
            synchronized (MemoryStore.this) {
                return checkIns.values().stream()
                        .filter(entry -> entry.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public List<CheckIn> byMember(String memberId, DateRange window) {
            synchronized (MemoryStore.this) {
                CrewMember member = members.get(memberId);
                String zone = member == null ? "UTC" : member.siteZone();
                return byMember(memberId).stream()
                        .filter(entry -> window.contains(Zones.civilDateIn(zone, entry.at())))
                        .toList();
            }
        }

        @Override
        public List<CheckIn> byHabit(String habitId) {
            synchronized (MemoryStore.this) {
                return checkIns.values().stream()
                        .filter(entry -> entry.habitId().equals(habitId))
                        .toList();
            }
        }

        @Override
        public void add(CheckIn entry) {
            synchronized (MemoryStore.this) {
                if (checkIns.containsKey(entry.id())) {
                    throw new ConflictException("check in " + entry.id() + " is already stored");
                }
                checkIns.put(entry.id(), entry);
            }
        }

        @Override
        public void replace(CheckIn entry) {
            synchronized (MemoryStore.this) {
                if (!checkIns.containsKey(entry.id())) {
                    throw new NotFoundException("check in", entry.id());
                }
                checkIns.put(entry.id(), entry);
            }
        }
    }

    private final class Overrides implements OverrideRepository {
        @Override
        public List<RosterOverride> byMember(String memberId) {
            synchronized (MemoryStore.this) {
                return overrides.stream()
                        .filter(row -> row.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public void add(RosterOverride entry) {
            synchronized (MemoryStore.this) {
                overrides.add(entry);
            }
        }
    }

    private final class Shields implements ShieldRepository {
        @Override
        public List<Shield> byMember(String memberId) {
            synchronized (MemoryStore.this) {
                return shields.stream()
                        .filter(row -> row.memberId().equals(memberId))
                        .toList();
            }
        }

        @Override
        public void add(Shield entry) {
            synchronized (MemoryStore.this) {
                shields.add(entry);
            }
        }
    }

    private final class Crews implements CrewRepository {
        @Override
        public Optional<Crew> byId(String id) {
            synchronized (MemoryStore.this) {
                return Optional.ofNullable(crews.get(id));
            }
        }

        @Override
        public List<Crew> all() {
            synchronized (MemoryStore.this) {
                return List.copyOf(crews.values());
            }
        }

        @Override
        public void save(Crew crew) {
            synchronized (MemoryStore.this) {
                crews.put(crew.id(), crew);
            }
        }

        @Override
        public List<Membership> memberships() {
            synchronized (MemoryStore.this) {
                return List.copyOf(memberships);
            }
        }

        @Override
        public void addMembership(Membership entry) {
            synchronized (MemoryStore.this) {
                memberships.add(entry);
            }
        }

        @Override
        public void replaceMembership(Membership entry) {
            synchronized (MemoryStore.this) {
                for (int index = 0; index < memberships.size(); index += 1) {
                    Membership row = memberships.get(index);
                    if (row.memberId().equals(entry.memberId()) && row.crewId().equals(entry.crewId())) {
                        memberships.set(index, entry);
                        return;
                    }
                }
                throw new NotFoundException("membership", entry.memberId());
            }
        }
    }

    private final class Sharing implements SharingRepository {
        @Override
        public Optional<SharingPreference> byMember(String memberId) {
            synchronized (MemoryStore.this) {
                return Optional.ofNullable(sharing.get(memberId));
            }
        }

        @Override
        public void save(SharingPreference preference) {
            synchronized (MemoryStore.this) {
                sharing.put(preference.memberId(), preference);
            }
        }
    }

    private final class Policies implements PolicyRepository {
        @Override
        public Optional<NudgePolicy> byMember(String memberId) {
            synchronized (MemoryStore.this) {
                return Optional.ofNullable(policies.get(memberId));
            }
        }

        @Override
        public void save(String memberId, NudgePolicy policy) {
            synchronized (MemoryStore.this) {
                policies.put(memberId, policy);
            }
        }
    }
}
