package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTemplate;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Collation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The habit use cases. Everything that changes a habit goes through here so there is one place
 * that checks the member is still on the books and one place that reads the clock. Every change
 * is announced on the bus once it is saved.
 */
public final class HabitsService {

    private final Ports ports;

    public HabitsService(Ports ports) {
        this.ports = ports;
    }

    /**
     * A new habit, written the way a form or a command line sends it: cadence and target as text,
     * dates as {@code YYYY-MM-DD}. Without a start date it starts today on the member's home clock.
     *
     * @param memberId whose habit it is
     * @param name what it is called
     * @param cadence how often it is owed, such as {@code 3/hitch}
     * @param target what counts as done, such as {@code at least 20 minutes}
     * @param startsOn the first day it asks
     * @param endsOn the last day it asks
     * @param tags short labels
     * @param graceDays how late a check in still counts
     */
    public record CreateHabitCommand(
            String memberId,
            String name,
            String cadence,
            Optional<String> target,
            Optional<String> startsOn,
            Optional<String> endsOn,
            List<String> tags,
            OptionalInt graceDays) {

        public CreateHabitCommand {
            tags = List.copyOf(tags);
        }

        public static CreateHabitCommand of(String memberId, String name, String cadence) {
            return new CreateHabitCommand(
                    memberId,
                    name,
                    cadence,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    OptionalInt.empty());
        }

        public CreateHabitCommand withTarget(String value) {
            return new CreateHabitCommand(
                    memberId, name, cadence, Optional.of(value), startsOn, endsOn, tags, graceDays);
        }

        public CreateHabitCommand withStartsOn(String value) {
            return new CreateHabitCommand(memberId, name, cadence, target, Optional.of(value), endsOn, tags, graceDays);
        }

        public CreateHabitCommand withEndsOn(String value) {
            return new CreateHabitCommand(
                    memberId, name, cadence, target, startsOn, Optional.of(value), tags, graceDays);
        }

        public CreateHabitCommand withTags(List<String> value) {
            return new CreateHabitCommand(memberId, name, cadence, target, startsOn, endsOn, value, graceDays);
        }

        public CreateHabitCommand withGraceDays(int value) {
            return new CreateHabitCommand(
                    memberId, name, cadence, target, startsOn, endsOn, tags, OptionalInt.of(value));
        }
    }

    private Habit load(String id) {
        return ports.habits().byId(id).orElseThrow(() -> new NotFoundException("habit", id));
    }

    private Habit saved(Habit entry, EventName name, String detail) {
        ports.habits().save(entry);
        ports.events()
                .ifPresent(bus -> bus.publish(DomainEvent.of(name, ports.clock().now(), entry.memberId())
                        .withHabit(entry.id())
                        .withDetail(detail)));
        return entry;
    }

    public Habit create(CreateHabitCommand command) {
        CrewMember member = ports.members()
                .byId(command.memberId())
                .orElseThrow(() -> new NotFoundException("crew member", command.memberId()));
        if (!member.isActive()) {
            throw ValidationException.field("memberId", "is not an active crew member");
        }
        Instant now = ports.clock().now();
        String id = Ids.habitId(ports.ids().next(IdKind.HABIT));
        Cadence cadence = Cadence.parse(command.cadence());
        Optional<HabitTarget> target = command.target().map(HabitTarget::parse);
        CivilDate startsOn = command.startsOn().isEmpty()
                ? Zones.civilDateIn(member.homeZone(), now)
                : CivilDate.parse(command.startsOn().get());
        Optional<CivilDate> endsOn = command.endsOn().map(CivilDate::parse);
        Habit entry = Habit.builder(id, member.id(), command.name(), cadence, startsOn, now)
                .target(target)
                .endsOn(endsOn)
                .tags(command.tags())
                .graceDays(command.graceDays().orElse(0))
                .build();
        return saved(entry, EventName.HABIT_CREATED, entry.name());
    }

    /** A new habit made from one of the standard templates. */
    public Habit applyTemplate(String memberId, String key, Optional<String> startsOn, Optional<String> endsOn) {
        HabitTemplate template = HabitTemplate.find(HabitTemplate.library(), key);
        return create(new CreateHabitCommand(
                memberId,
                template.name(),
                template.cadence().format(),
                template.target().map(HabitTarget::format),
                startsOn,
                endsOn,
                template.tags(),
                OptionalInt.of(template.graceDays())));
    }

    public Habit get(String id) {
        return load(id);
    }

    /** The member's habits, by name. */
    public List<Habit> listFor(String memberId) {
        List<Habit> found = new ArrayList<>(ports.habits().byMember(memberId));
        found.sort(Comparator.comparing(Habit::name, Collation.ORDER));
        return found;
    }

    public Habit rename(String id, String name) {
        return saved(load(id).rename(name), EventName.HABIT_CHANGED, "renamed to " + name);
    }

    /** Moves the habit to {@code status}; archiving is announced as such. */
    public Habit moveTo(String id, HabitStatus status) {
        Habit moved = load(id).moveTo(status);
        EventName event = status == HabitStatus.ARCHIVED ? EventName.HABIT_ARCHIVED : EventName.HABIT_CHANGED;
        return saved(moved, event, "status " + status);
    }

    public Habit changeCadence(String id, String cadence) {
        Habit changed = load(id).recadence(Cadence.parse(cadence));
        return saved(changed, EventName.HABIT_CHANGED, "cadence " + cadence);
    }

    /** Sets the target from its text, or clears it with an empty one. */
    public Habit changeTarget(String id, Optional<String> target) {
        Optional<HabitTarget> parsed = target.map(HabitTarget::parse);
        return saved(load(id).retarget(parsed), EventName.HABIT_CHANGED, "target " + target.orElse("cleared"));
    }

    public Habit changeTags(String id, List<String> tags) {
        return saved(load(id).retag(tags), EventName.HABIT_CHANGED, "tags " + String.join(" ", tags));
    }
}
