package io.github.rkaushikdev.hitchline.http.routes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordResult;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Pagination;
import io.github.rkaushikdev.hitchline.http.Pagination.PageBody;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.http.Schemas;
import io.github.rkaushikdev.hitchline.http.Schemas.AmendCheckInBody;
import io.github.rkaushikdev.hitchline.http.Schemas.RecordCheckInBody;
import io.github.rkaushikdev.hitchline.http.Views;
import io.github.rkaushikdev.hitchline.http.Views.CheckInView;
import io.github.rkaushikdev.hitchline.http.Views.ResolvedCheckInView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Check in routes. Recording is the hot path: the handset posts one small body and gets the stored
 * row back, and a repeat of the same idempotency key gets the first row rather than a second one.
 */
@RestController
public final class CheckInRoutes {

    private final Ports ports;
    private final CheckInsService checkIns;
    private final HabitsService habits;

    public CheckInRoutes(Ports ports) {
        this.ports = ports;
        this.checkIns = new CheckInsService(ports);
        this.habits = new HabitsService(ports);
    }

    /** Registers the check in routes on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new CheckInRoutes(ports));
    }

    @PostMapping("/check-ins")
    public ResponseEntity<CheckInView> record(
            @RequestBody(required = false) JsonNode payload, HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        RecordCheckInBody body = Schemas.parseBody(Schemas.RECORD_CHECK_IN, payload);
        Habit habit = habits.get(Ids.habitId(body.habitId()));
        Auth.requireSelfOrAdmin(request, habit.memberId());

        RecordResult result = checkIns.record(new RecordCheckInCommand(
                habit.id(), body.at(), body.reading(), body.note(), body.source(), body.idempotencyKey()));
        return ResponseEntity.status(result.accepted() ? 201 : 200).body(Views.checkInView(result.entry()));
    }

    @GetMapping("/members/{memberId}/check-ins")
    public PageBody<ResolvedCheckInView> list(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        List<ResolvedCheckIn> found = checkIns.listFor(member, Query.optionalWindowOf(request));
        return Pagination.pageBody(
                Pagination.paginate(found, Pagination.readPageRequest(request), item -> item.checkIn()
                        .id()),
                Views::resolvedView);
    }

    @PostMapping("/check-ins/{checkInId}/void")
    public CheckInView voidEntry(@PathVariable("checkInId") String checkInId, HttpServletRequest request) {
        String id = Ids.checkInId(checkInId);
        CheckIn existing = ports.checkIns().byId(id).orElseThrow(() -> new NotFoundException("check in", id));
        Auth.requireSelfOrAdmin(request, existing.memberId());
        Auth.requireScope(request, Scope.WRITE);
        return Views.checkInView(checkIns.voidEntry(id));
    }

    @PatchMapping("/check-ins/{checkInId}")
    public CheckInView amend(
            @PathVariable("checkInId") String checkInId,
            @RequestBody(required = false) JsonNode payload,
            HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        AmendCheckInBody body = Schemas.parseBody(Schemas.AMEND_CHECK_IN, payload);
        String id = Ids.checkInId(checkInId);
        CheckIn existing = ports.checkIns().byId(id).orElseThrow(() -> new NotFoundException("check in", id));
        Auth.requireSelfOrAdmin(request, existing.memberId());
        return Views.checkInView(checkIns.amend(id, body.reading()));
    }
}
