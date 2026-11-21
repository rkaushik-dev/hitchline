package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;

/**
 * Demo data. Somebody trying the thing out needs a crew with a fortnight of history behind it, or
 * every report reads as empty and the tool looks broken. The generator is deterministic, so two
 * people running it see the same crew and can talk about the same numbers.
 */
public final class Seed {

    private record Person(String name, String home, int phase) {}

    private record Template(String name, String cadence, Optional<String> target) {}

    private static final List<Person> PEOPLE = List.of(
            new Person("Ada Vasquez", "Europe/Lisbon", 0),
            new Person("Bo Lindqvist", "Europe/Stockholm", 14),
            new Person("Cleo Nkemdirim", "Europe/London", 0),
            new Person("Dai Roberts", "Europe/London", 14),
            new Person("Eero Salo", "Europe/Helsinki", 0));

    private static final List<Template> HABITS = List.of(
            new Template("Twenty minutes on the treadmill", "1/onshift-day", Optional.of("at least 20 minutes")),
            new Template("Bank the sleep hours", "1/day", Optional.of("at least 7 count")),
            new Template("Toolbox talk read through", "3/hitch", Optional.empty()),
            new Template("Call home", "1/offshift-block", Optional.empty()));

    private Seed() {}

    /**
     * How much demo data to make.
     *
     * @param from the first day of history
     * @param days how many days of it
     * @param adherence roughly what share of what is owed gets done, between zero and one
     * @param seed the generator's seed
     */
    public record SeedOptions(String from, int days, double adherence, int seed) {

        /** Four weeks from 6 January 2025, about four in five done. */
        public static final SeedOptions DEFAULT = new SeedOptions("2025-01-06", 28, 0.8, 7);

        public SeedOptions withFrom(String value) {
            return new SeedOptions(value, days, adherence, seed);
        }

        public SeedOptions withDays(int value) {
            return new SeedOptions(from, value, adherence, seed);
        }

        public SeedOptions withAdherence(double value) {
            return new SeedOptions(from, days, value, seed);
        }

        public SeedOptions withSeed(int value) {
            return new SeedOptions(from, days, adherence, value);
        }
    }

    /**
     * What a seed made.
     *
     * @param crewId the crew
     * @param members how many members
     * @param habits how many habits
     * @param checkIns how many check ins
     * @param from the first day of history
     * @param to the last day of history
     */
    public record SeedReport(String crewId, int members, int habits, int checkIns, CivilDate from, CivilDate to) {}

    /**
     * A small deterministic generator, an xorshift over 32 bits, so a demo looks the same twice
     * running. Each reading is between zero and one.
     */
    public static DoubleSupplier generator(int seed) {
        int[] state = {seed == 0 ? 1 : seed};
        return () -> {
            int value = state[0];
            value ^= value << 13;
            value ^= value >>> 17;
            value ^= value << 5;
            state[0] = value;
            return Integer.toUnsignedLong(value) / 4_294_967_295.0;
        };
    }

    /** Deterministic ids in the domain alphabet: {@code index} written in base 32 at the end. */
    static String idFor(IdKind kind, int index) {
        byte[] bytes = new byte[16];
        int value = index;
        for (int position = 15; position >= 0 && value > 0; position -= 1) {
            bytes[position] = (byte) (value % 32);
            value /= 32;
        }
        return Ids.mintId(kind, bytes);
    }

    public static SeedReport seedDemo(Ports ports) {
        return seedDemo(ports, SeedOptions.DEFAULT);
    }

    /** A crew of five on a fortnight rota with four habits each and the history the options ask for. */
    public static SeedReport seedDemo(Ports ports, SeedOptions options) {
        CivilDate from = CivilDate.parse(options.from());
        double adherence = Math.min(1, Math.max(0, options.adherence()));
        DoubleSupplier next = generator(options.seed());
        String site = "Europe/Oslo";
        Crew alpha = new Crew(Ids.crewId(idFor(IdKind.CREW, 1)), "Alpha production", "Grane B", site);
        ports.crews().save(alpha);

        int habitCount = 0;
        int checkInCount = 0;
        for (int index = 0; index < PEOPLE.size(); index += 1) {
            Person person = PEOPLE.get(index);
            CrewMember member = CrewMember.of(
                    Ids.crewMemberId(idFor(IdKind.CREW_MEMBER, index + 1)),
                    person.name(),
                    person.home(),
                    site,
                    RotationAssignment.of(RotationPattern.parse("14/14"), from, person.phase()),
                    from.plusDays(-180));
            ports.members().save(member);
            ports.crews()
                    .addMembership(Membership.of(
                            alpha.id(),
                            member.id(),
                            from.plusDays(-180),
                            index == 0 ? CrewRole.LEAD : CrewRole.MEMBER));

            for (int position = 0; position < HABITS.size(); position += 1) {
                Template template = HABITS.get(position);
                habitCount += 1;
                Habit entry = Habit.builder(
                                Ids.habitId(idFor(IdKind.HABIT, habitCount)),
                                member.id(),
                                template.name(),
                                Cadence.parse(template.cadence()),
                                from,
                                Zones.zonedInstant(site, from, ClockTime.parse("06:00")))
                        .target(template.target().map(HabitTarget::parse))
                        .tags(position == 0 ? List.of("fitness") : List.of())
                        .build();
                ports.habits().save(entry);

                for (int day = 0; day < options.days(); day += 1) {
                    CivilDate date = from.plusDays(day);
                    DayRole role = member.rotation().roleOn(date);
                    if (template.cadence().contains("onshift") && role != DayRole.ONSHIFT) {
                        continue;
                    }
                    if (template.cadence().contains("offshift") && role != DayRole.OFFSHIFT) {
                        continue;
                    }
                    if (next.getAsDouble() > adherence) {
                        continue;
                    }
                    checkInCount += 1;
                    Instant at = Zones.zonedInstant(
                            role == DayRole.ONSHIFT ? site : person.home(),
                            date,
                            ClockTime.parse(next.getAsDouble() > 0.5 ? "07:30" : "18:15"));
                    OptionalDouble reading = template.target().isEmpty()
                            ? OptionalDouble.empty()
                            : OptionalDouble.of(Numbers.round(15 + next.getAsDouble() * 20));
                    ports.checkIns()
                            .add(CheckIn.builder(
                                            Ids.checkInId(idFor(IdKind.CHECK_IN, checkInCount)),
                                            entry.id(),
                                            member.id(),
                                            at,
                                            at,
                                            CheckInSource.APP)
                                    .reading(reading)
                                    .build());
                }
            }
        }
        return new SeedReport(
                alpha.id(), PEOPLE.size(), habitCount, checkInCount, from, from.plusDays(options.days() - 1L));
    }
}
