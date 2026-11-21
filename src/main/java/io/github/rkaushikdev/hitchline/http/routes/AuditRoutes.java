package io.github.rkaushikdev.hitchline.http.routes;

import io.github.rkaushikdev.hitchline.app.AuditEntry;
import io.github.rkaushikdev.hitchline.app.AuditLog;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Pagination;
import io.github.rkaushikdev.hitchline.http.Pagination.PageBody;
import io.github.rkaushikdev.hitchline.http.Query;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail over http. It is admin only and it is a read: nothing writes here, because an
 * audit line somebody can post is not an audit line.
 */
@RestController
public final class AuditRoutes {

    private final AuditLog log;

    public AuditRoutes(AuditLog log) {
        this.log = log;
    }

    /** Registers the audit route on {@code app}, reading {@code log}. */
    public static void register(HttpApp app, AuditLog log) {
        app.addRoutes(new AuditRoutes(log));
    }

    /**
     * One line of the trail.
     *
     * @param sequence its place in the trail, from one
     * @param name what happened
     * @param at when
     * @param memberId whose record it was
     * @param habitId the habit, when one was involved
     * @param checkInId the check in, when one was involved
     * @param crewId the crew, when one was involved
     * @param detail what changed
     * @param line the whole entry on one line
     */
    public record AuditView(
            long sequence,
            String name,
            String at,
            String memberId,
            Optional<String> habitId,
            Optional<String> checkInId,
            Optional<String> crewId,
            String detail,
            String line) {}

    private static AuditView auditView(AuditEntry entry) {
        return new AuditView(
                entry.sequence(),
                entry.name().wire(),
                Zones.formatInstant(entry.at()),
                entry.memberId(),
                entry.habitId(),
                entry.checkInId(),
                entry.crewId(),
                entry.detail(),
                entry.event().describe());
    }

    @GetMapping("/audit")
    public PageBody<AuditView> audit(HttpServletRequest request) {
        Auth.requireScope(request, Scope.ADMIN);
        Optional<DateRange> window = Query.optionalWindowOf(request);
        String zone = Query.stringOf(request, "zone").orElse("UTC");

        List<AuditEntry> entries =
                window.map(range -> log.inWindow(range, zone)).orElseGet(log::all);
        if (Query.stringOf(request, "memberId").isPresent()) {
            String memberId = Query.queryMemberOf(request, "read the audit trail");
            entries = entries.stream()
                    .filter(entry -> entry.memberId().equals(memberId))
                    .toList();
        }

        List<AuditEntry> newestFirst = new ArrayList<>(entries);
        Collections.reverse(newestFirst);
        return Pagination.pageBody(
                Pagination.paginate(
                        newestFirst, Pagination.readPageRequest(request), entry -> String.valueOf(entry.sequence())),
                AuditRoutes::auditView);
    }
}
