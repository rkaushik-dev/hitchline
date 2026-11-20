package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;

/**
 * Which clock a day is measured on. An on shift day belongs to the site and an off shift or
 * standby day to home. A travel day belongs to wherever the person is heading, because the flight
 * lands before the day is over and everything they are asked to do that evening happens at the far
 * end.
 */
public final class Zoning {

    private Zoning() {}

    private static ZoneReason travelReason(CrewMember member, CivilDate date) {
        RotationAssignment rotation = member.rotation();
        DayRole nextRole = rotation.roleOn(date.plusDays(1));
        if (nextRole == DayRole.ONSHIFT) {
            return ZoneReason.OUTBOUND;
        }
        if (nextRole == DayRole.TRAVEL) {
            // A pair of travel days in a row. Look one further on before deciding.
            return rotation.roleOn(date.plusDays(2)) == DayRole.ONSHIFT ? ZoneReason.OUTBOUND : ZoneReason.INBOUND;
        }
        return ZoneReason.INBOUND;
    }

    public static ZoneChoice zoneOn(CrewMember member, CivilDate date) {
        return switch (member.rotation().roleOn(date)) {
            case ONSHIFT -> new ZoneChoice(member.siteZone(), ZoneReason.SITE);
            case TRAVEL -> {
                ZoneReason reason = travelReason(member, date);
                yield new ZoneChoice(reason == ZoneReason.OUTBOUND ? member.siteZone() : member.homeZone(), reason);
            }
            case OFFSHIFT, STANDBY -> new ZoneChoice(member.homeZone(), ZoneReason.HOME);
        };
    }

    /**
     * Works out which rotation day an instant belongs to. The site clock is read first because that
     * is where the work is; if the zone that day turns out to be the home one, the reading is taken
     * again there. When the two disagree about the date the day is marked as straddled so a report
     * can say so.
     */
    public static RotationDay rotationDayAt(CrewMember member, Instant at) {
        RotationAssignment rotation = member.rotation();
        CivilDate siteDate = Zones.civilDateIn(member.siteZone(), at);
        ZoneChoice first = zoneOn(member, siteDate);
        CivilDate settledDate = Zones.civilDateIn(first.zone(), at);
        if (siteDate.isSameDay(settledDate)) {
            return new RotationDay(siteDate, rotation.roleOn(siteDate), first.zone(), first.reason(), false);
        }
        ZoneChoice second = zoneOn(member, settledDate);
        if (second.zone().equals(first.zone())) {
            return new RotationDay(settledDate, rotation.roleOn(settledDate), second.zone(), second.reason(), true);
        }
        // The two readings pull in opposite directions, which happens on the day a rotation turns
        // over. The site clock wins so the shift keeps its own day.
        return new RotationDay(siteDate, rotation.roleOn(siteDate), member.siteZone(), ZoneReason.SITE, true);
    }

    public static RotationDay rotationDayOn(CrewMember member, CivilDate date) {
        ZoneChoice choice = zoneOn(member, date);
        return new RotationDay(date, member.rotation().roleOn(date), choice.zone(), choice.reason(), false);
    }
}
