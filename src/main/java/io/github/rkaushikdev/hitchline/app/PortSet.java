package io.github.rkaushikdev.hitchline.app;

import java.util.Objects;
import java.util.Optional;

/**
 * A set of ports held as values, for wiring one set of repositories to another bus or clock.
 *
 * @param events where services announce what they did
 * @param clock the time
 * @param ids new record ids
 * @param members crew members
 * @param habits habits
 * @param checkIns check ins
 * @param overrides roster entries
 * @param shields shields
 * @param crews crews and memberships
 * @param sharing sharing settings
 * @param policies nudge policies
 */
public record PortSet(
        Optional<EventBus> events,
        Clock clock,
        IdSource ids,
        MemberRepository members,
        HabitRepository habits,
        CheckInRepository checkIns,
        OverrideRepository overrides,
        ShieldRepository shields,
        CrewRepository crews,
        SharingRepository sharing,
        PolicyRepository policies)
        implements Ports {

    public PortSet {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ids, "ids");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(habits, "habits");
        Objects.requireNonNull(checkIns, "checkIns");
        Objects.requireNonNull(overrides, "overrides");
        Objects.requireNonNull(shields, "shields");
        Objects.requireNonNull(crews, "crews");
        Objects.requireNonNull(sharing, "sharing");
        Objects.requireNonNull(policies, "policies");
    }

    /** The ports of {@code source}, held as values. */
    public static PortSet copyOf(Ports source) {
        if (source instanceof PortSet set) {
            return set;
        }
        return new PortSet(
                source.events(),
                source.clock(),
                source.ids(),
                source.members(),
                source.habits(),
                source.checkIns(),
                source.overrides(),
                source.shields(),
                source.crews(),
                source.sharing(),
                source.policies());
    }

    public PortSet withEvents(Optional<EventBus> bus) {
        return new PortSet(bus, clock, ids, members, habits, checkIns, overrides, shields, crews, sharing, policies);
    }

    @Override
    public PortSet withClock(Clock other) {
        return new PortSet(events, other, ids, members, habits, checkIns, overrides, shields, crews, sharing, policies);
    }
}
