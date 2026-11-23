package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;

/**
 * Which clock a day is measured on. An on shift day belongs to the site and an off shift or
 * standby day to home. A travel day belongs to wherever the person is heading, because the flight
 * lands before the day is over and everything they are asked to do that evening happens at the far
 * end. The roles are read from the roster, entries included, so a day somebody was held over
 * offshore is measured on the site clock and not on the home one the pattern had down for it.
 */
public final class Zoning {

    private Zoning() {}

    private static ZoneReason travelReason(Roster roster, CivilDate date) {
        DayRole nextRole = roster.roleFor(date.plusDays(1));
        if (nextRole == DayRole.ONSHIFT) {
            return ZoneReason.OUTBOUND;
        }
        if (nextRole == DayRole.TRAVEL) {
            // A pair of travel days in a row. Look one further on before deciding.
            return roster.roleFor(date.plusDays(2)) == DayRole.ONSHIFT ? ZoneReason.OUTBOUND : ZoneReason.INBOUND;
        }
        return ZoneReason.INBOUND;
    }

    /** The clock a day of the roster is measured on, and why. */
    public static ZoneChoice zoneOn(Roster roster, CivilDate date) {
        CrewMember member = roster.member();
        return switch (roster.roleFor(date)) {
            case ONSHIFT -> new ZoneChoice(member.siteZone(), ZoneReason.SITE);
            case TRAVEL -> {
                ZoneReason reason = travelReason(roster, date);
                yield new ZoneChoice(reason == ZoneReason.OUTBOUND ? member.siteZone() : member.homeZone(), reason);
            }
            case OFFSHIFT, STANDBY -> new ZoneChoice(member.homeZone(), ZoneReason.HOME);
        };
    }

    /** The clock a day of the bare pattern is measured on. */
    public static ZoneChoice zoneOn(CrewMember member, CivilDate date) {
        return zoneOn(Roster.of(member), date);
    }

    /**
     * Works out which rotation day an instant belongs to. The site clock is read first because that
     * is where the work is; if the zone that day turns out to be the home one, the reading is taken
     * again there. When the two disagree about the date the day is marked as straddled so a report
     * can say so.
     */
    public static RotationDay rotationDayAt(Roster roster, Instant at) {
        String site = roster.member().siteZone();
        CivilDate siteDate = Zones.civilDateIn(site, at);
        ZoneChoice first = zoneOn(roster, siteDate);
        CivilDate settledDate = Zones.civilDateIn(first.zone(), at);
        if (siteDate.isSameDay(settledDate)) {
            return new RotationDay(siteDate, roster.roleFor(siteDate), first.zone(), first.reason(), false);
        }
        ZoneChoice second = zoneOn(roster, settledDate);
        if (second.zone().equals(first.zone())) {
            return new RotationDay(settledDate, roster.roleFor(settledDate), second.zone(), second.reason(), true);
        }
        // The two readings pull in opposite directions, which happens on the day a rotation turns
        // over. The site clock wins so the shift keeps its own day.
        return new RotationDay(siteDate, roster.roleFor(siteDate), site, ZoneReason.SITE, true);
    }

    /** The rotation day of an instant on the bare pattern. */
    public static RotationDay rotationDayAt(CrewMember member, Instant at) {
        return rotationDayAt(Roster.of(member), at);
    }

    public static RotationDay rotationDayOn(Roster roster, CivilDate date) {
        ZoneChoice choice = zoneOn(roster, date);
        return new RotationDay(date, roster.roleFor(date), choice.zone(), choice.reason(), false);
    }

    public static RotationDay rotationDayOn(CrewMember member, CivilDate date) {
        return rotationDayOn(Roster.of(member), date);
    }
}
