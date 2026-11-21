package io.github.rkaushikdev.hitchline.http.routes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.http.Schemas;
import io.github.rkaushikdev.hitchline.http.Schemas.HoldBody;
import io.github.rkaushikdev.hitchline.http.Schemas.ShieldBody;
import io.github.rkaushikdev.hitchline.http.Views;
import io.github.rkaushikdev.hitchline.http.Views.OverrideView;
import io.github.rkaushikdev.hitchline.http.Views.ShieldView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roster routes. Reading a roster is open to anybody who may read the member; changing one needs the
 * write scope, because a hold moves what the person is measured against.
 */
@RestController
public final class RosterRoutes {

    private final RosterService rosters;

    public RosterRoutes(Ports ports) {
        this.rosters = new RosterService(ports);
    }

    /** Registers the roster routes on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new RosterRoutes(ports));
    }

    /**
     * One day of a roster.
     *
     * @param date the day
     * @param role what the member does that day
     * @param source where the role came from
     * @param zone the zone the day is measured in
     */
    public record DayView(String date, String role, String source, String zone) {}

    /**
     * A roster over a window.
     *
     * @param days one per day, in order
     */
    public record RosterBody(List<DayView> days) {}

    /**
     * The hitch a day sits in.
     *
     * @param hitch its days
     */
    public record HitchBody(String hitch) {}

    @GetMapping("/members/{memberId}/roster")
    public RosterBody roster(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        return new RosterBody(rosters.daysFor(member, Query.windowStringOf(request, "read a roster")).stream()
                .map(day -> new DayView(
                        day.date().toString(), day.role().wire(), day.source().wire(), day.zone()))
                .toList());
    }

    @GetMapping("/members/{memberId}/hitch")
    public HitchBody hitch(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        String on = Query.rawDateOf(request, "on", "find a hitch");
        return new HitchBody(rosters.hitchOn(member, on).toString());
    }

    @PostMapping("/roster/holds")
    public ResponseEntity<OverrideView> hold(
            @RequestBody(required = false) JsonNode payload, HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        HoldBody body = Schemas.parseBody(Schemas.HOLD, payload);
        String member = Ids.crewMemberId(body.memberId());
        Auth.requireSelfOrAdmin(request, member);
        return ResponseEntity.status(201)
                .body(Views.overrideView(
                        rosters.hold(new HoldCommand(member, body.range(), body.role(), body.reason(), body.note()))));
    }

    @PostMapping("/roster/shields")
    public ResponseEntity<ShieldView> shield(
            @RequestBody(required = false) JsonNode payload, HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        ShieldBody body = Schemas.parseBody(Schemas.SHIELD, payload);
        String member = Ids.crewMemberId(body.memberId());
        Auth.requireSelfOrAdmin(request, member);
        return ResponseEntity.status(201)
                .body(Views.shieldView(rosters.shieldDays(new ShieldCommand(
                        member,
                        body.range(),
                        body.reason(),
                        body.note(),
                        body.grantedBy().map(Ids::crewMemberId)))));
    }
}
