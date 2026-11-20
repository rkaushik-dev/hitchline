package io.github.rkaushikdev.hitchline.app;

import java.util.Optional;

/**
 * Everything the application layer needs from the outside world. The domain knows none of this;
 * the services take these in so a test can hand them a fixed clock and a map, and production can
 * hand them a journal on disk. The whole set is passed around as one so a service takes a single
 * argument.
 */
public interface Ports {

    /** Where services announce what they did, when anything is listening. */
    Optional<EventBus> events();

    Clock clock();

    IdSource ids();

    MemberRepository members();

    HabitRepository habits();

    CheckInRepository checkIns();

    OverrideRepository overrides();

    ShieldRepository shields();

    CrewRepository crews();

    SharingRepository sharing();

    PolicyRepository policies();

    /** The same ports announcing to {@code bus}. */
    default Ports withEvents(EventBus bus) {
        return PortSet.copyOf(this).withEvents(Optional.of(bus));
    }

    /** The same ports reading the time from {@code other}. */
    default Ports withClock(Clock other) {
        return PortSet.copyOf(this).withClock(other);
    }
}
