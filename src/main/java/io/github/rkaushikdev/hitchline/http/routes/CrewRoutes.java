package io.github.rkaushikdev.hitchline.http.routes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.CrewService;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.http.Schemas;
import io.github.rkaushikdev.hitchline.http.Schemas.CreateCrewBody;
import io.github.rkaushikdev.hitchline.http.Schemas.EnrolBody;
import io.github.rkaushikdev.hitchline.http.Schemas.VisibilityBody;
import io.github.rkaushikdev.hitchline.http.Views;
import io.github.rkaushikdev.hitchline.http.Views.CrewView;
import io.github.rkaushikdev.hitchline.http.Views.MemberView;
import io.github.rkaushikdev.hitchline.http.Views.MembershipView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crew routes. Creating a crew and moving people between them is an admin job; a member decides
 * their own sharing and nobody else can.
 */
@RestController
public final class CrewRoutes {

    private final CrewService crews;

    public CrewRoutes(Ports ports) {
        this.crews = new CrewService(ports);
    }

    /** Registers the crew routes on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new CrewRoutes(ports));
    }

    /**
     * Who was in a crew on a day.
     *
     * @param members in name order
     */
    public record MembersBody(List<MemberView> members) {}

    /**
     * One habit set apart from the fallback.
     *
     * @param habitId which habit
     * @param visibility what it shows
     */
    public record HabitVisibility(String habitId, Visibility visibility) {}

    /**
     * Somebody's sharing.
     *
     * @param fallback what every habit not named separately shows
     * @param perHabit the habits set apart, in the order they were set
     */
    public record VisibilityView(Visibility fallback, List<HabitVisibility> perHabit) {}

    private static VisibilityView visibilityView(SharingPreference updated) {
        return new VisibilityView(
                updated.fallback(),
                updated.perHabit().entrySet().stream()
                        .map(entry -> new HabitVisibility(entry.getKey(), entry.getValue()))
                        .toList());
    }

    @PostMapping("/crews")
    public ResponseEntity<CrewView> create(
            @RequestBody(required = false) JsonNode payload, HttpServletRequest request) {
        Auth.requireScope(request, Scope.ADMIN);
        CreateCrewBody body = Schemas.parseBody(Schemas.CREATE_CREW, payload);
        return ResponseEntity.status(201).body(Views.crewView(crews.create(body.name(), body.site(), body.siteZone())));
    }

    @PostMapping("/crews/{crewId}/members")
    public ResponseEntity<MembershipView> enrol(
            @PathVariable("crewId") String crewId,
            @RequestBody(required = false) JsonNode payload,
            HttpServletRequest request) {
        Auth.requireScope(request, Scope.ADMIN);
        EnrolBody body = Schemas.parseBody(Schemas.ENROL, payload);
        return ResponseEntity.status(201)
                .body(Views.membershipView(
                        crews.enrol(Ids.crewId(crewId), Ids.crewMemberId(body.memberId()), body.from(), body.role())));
    }

    @GetMapping("/crews/{crewId}/members")
    public MembersBody members(@PathVariable("crewId") String crewId, HttpServletRequest request) {
        Auth.requireScope(request, Scope.READ);
        String on = Query.stringOf(request, "on")
                .orElseThrow(() -> ValidationException.field("on", "is needed to read a crew"));
        return new MembersBody(crews.membersOfCrew(Ids.crewId(crewId), on).stream()
                .map(Views::memberView)
                .toList());
    }

    @PutMapping("/members/{memberId}/visibility")
    public VisibilityView visibility(
            @PathVariable("memberId") String memberId,
            @RequestBody(required = false) JsonNode payload,
            HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        Auth.requireScope(request, Scope.WRITE);
        VisibilityBody body = Schemas.parseBody(Schemas.VISIBILITY, payload);

        if (body.habitId().isPresent()) {
            Visibility visibility = body.visibility()
                    .orElseThrow(() -> ValidationException.field("visibility", "is needed alongside a habit id"));
            return visibilityView(
                    crews.setHabitVisibility(member, Ids.habitId(body.habitId().get()), visibility));
        }

        Visibility fallback = body.fallback()
                .orElseThrow(() -> ValidationException.field("fallback", "is needed when no habit is named"));
        return visibilityView(crews.setVisibility(member, fallback));
    }
}
