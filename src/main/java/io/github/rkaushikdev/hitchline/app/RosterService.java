package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.DutyBreach;
import io.github.rkaushikdev.hitchline.domain.rotation.DutyLimits;
import io.github.rkaushikdev.hitchline.domain.rotation.EffectiveRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RoleSource;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Everything about somebody's calendar. The service exists mainly so that the roster is always
 * assembled the same way: the member, plus every override stored for them, and never one without
 * the other.
 */
public final class RosterService {

    private final Ports ports;

    public RosterService(Ports ports) {
        this.ports = ports;
    }

    /**
     * One day of somebody's roster.
     *
     * @param date the day
     * @param role what it is
     * @param source whether the pattern or an override decided it
     * @param zone the clock the day is measured on
     */
    public record RosterDay(CivilDate date, DayRole role, RoleSource source, String zone) {

        /** One line, such as {@code 2025-01-10 onshift (pattern)}. */
        public String describe() {
            return date + " " + role + " (" + source + ")";
        }
    }

    /**
     * Days held to a role other than the pattern's.
     *
     * @param memberId whose roster
     * @param range the days, {@code YYYY-MM-DD..YYYY-MM-DD}
     * @param role what they are instead
     * @param reason why
     * @param note anything the clerk wrote
     * @param limits the duty limits the held roster must stay inside, when they are to be checked
     */
    public record HoldCommand(
            String memberId,
            String range,
            DayRole role,
            OverrideReason reason,
            Optional<String> note,
            Optional<DutyLimits> limits) {

        /** A hold that is stored whatever it does to the duty limits. */
        public HoldCommand(String memberId, String range, DayRole role, OverrideReason reason, Optional<String> note) {
            this(memberId, range, role, reason, note, Optional.empty());
        }

        /** The same hold, checked against {@code value}. */
        public HoldCommand withinLimits(DutyLimits value) {
            return new HoldCommand(memberId, range, role, reason, note, Optional.of(value));
        }
    }

    /**
     * Days shielded from scoring.
     *
     * @param memberId whose days
     * @param range the days, {@code YYYY-MM-DD..YYYY-MM-DD}
     * @param reason why
     * @param note anything written with it
     * @param grantedBy the supervisor granting it, when it is not self declared
     */
    public record ShieldCommand(
            String memberId, String range, ShieldReason reason, Optional<String> note, Optional<String> grantedBy) {}

    /**
     * A new rotation for somebody.
     *
     * @param memberId who
     * @param pattern the pattern as people write it, such as {@code 14/14}
     * @param anchor the date at the phase position
     * @param phase how far into the cycle the anchor is; zero when absent
     */
    public record ReassignCommand(String memberId, String pattern, String anchor, OptionalInt phase) {}

    private CrewMember loadMember(String memberId) {
        return ports.members().byId(memberId).orElseThrow(() -> new NotFoundException("crew member", memberId));
    }

    public Roster rosterFor(String memberId) {
        CrewMember member = loadMember(memberId);
        return Roster.of(member, ports.overrides().byMember(memberId));
    }

    /** Every day of {@code window} with its role, where the role came from and its clock. */
    public List<RosterDay> daysFor(String memberId, String window) {
        Roster target = rosterFor(memberId);
        List<RosterDay> days = new ArrayList<>();
        for (CivilDate date : DateRange.parse(window).days()) {
            EffectiveRole effective = target.effectiveRoleOn(date);
            days.add(new RosterDay(
                    date,
                    effective.role(),
                    effective.source(),
                    Zoning.zoneOn(target.member(), date).zone()));
        }
        return days;
    }

    /** The trips and breaks of the member's roster touching {@code window} that break {@code limits}. */
    public List<DutyBreach> dutyBreaches(String memberId, String window, DutyLimits limits) {
        return limits.check(rosterFor(memberId), DateRange.parse(window));
    }

    /** The hitch {@code date} belongs to. */
    public DateRange hitchOn(String memberId, String date) {
        return loadMember(memberId).rotation().hitchRangeOn(CivilDate.parse(date));
    }

    /**
     * Stores an override, refusing one that covers a day another of the member's entries covers.
     * When the command carries duty limits it also refuses a hold that makes a trip or a break
     * break them, unless the reason is the weather: nobody can be flown home through a grounded
     * helicopter, so a weather hold is recorded and the breach shows up on the roster check instead.
     */
    public RosterOverride hold(HoldCommand command) {
        CrewMember member = loadMember(command.memberId());
        RosterOverride entry = new RosterOverride(
                member.id(),
                DateRange.parse(command.range()),
                command.role(),
                command.reason(),
                command.note().orElse(""),
                ports.clock().now());
        List<RosterOverride> before = ports.overrides().byMember(member.id());
        List<RosterOverride> after = new ArrayList<>(before);
        after.add(entry);
        RosterOverride.checkNoClashes(after);
        if (command.limits().isPresent() && entry.reason() != OverrideReason.WEATHER) {
            DutyLimits limits = command.limits().get();
            // The runs either side of the entry change shape too, so they are read as well.
            DateRange touched = DateRange.of(
                    entry.range().from().plusDays(-1), entry.range().to().plusDays(1));
            List<DutyBreach> already = limits.check(Roster.of(member, before), touched);
            limits.check(Roster.of(member, after), touched).stream()
                    .filter(breach -> !already.contains(breach))
                    .findFirst()
                    .ifPresent(breach -> {
                        throw new ConflictException("holding " + entry.range() + " leaves " + breach.describe());
                    });
        }
        ports.overrides().add(entry);
        ports.events()
                .ifPresent(bus -> bus.publish(
                        DomainEvent.of(EventName.ROSTER_HELD, ports.clock().now(), member.id())
                                .withDetail(command.range() + " as " + command.role())));
        return entry;
    }

    public Shield shieldDays(ShieldCommand command) {
        CrewMember member = loadMember(command.memberId());
        Shield entry = new Shield(
                member.id(),
                DateRange.parse(command.range()),
                command.reason(),
                command.note().orElse(""),
                command.grantedBy(),
                ports.clock().now());
        ports.shields().add(entry);
        ports.events()
                .ifPresent(bus -> bus.publish(
                        DomainEvent.of(EventName.SHIELD_GRANTED, ports.clock().now(), member.id())
                                .withDetail(command.range() + " for " + command.reason())));
        return entry;
    }

    public CrewMember reassignRotation(ReassignCommand command) {
        CrewMember member = loadMember(command.memberId());
        RotationAssignment assignment = RotationAssignment.of(
                RotationPattern.parse(command.pattern()),
                CivilDate.parse(command.anchor()),
                command.phase().orElse(0));
        CrewMember updated = member.reassign(assignment);
        ports.members().save(updated);
        return updated;
    }
}
