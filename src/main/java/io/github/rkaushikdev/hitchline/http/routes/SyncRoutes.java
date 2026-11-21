package io.github.rkaushikdev.hitchline.http.routes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.app.SyncService;
import io.github.rkaushikdev.hitchline.app.SyncService.QueuedCheckIn;
import io.github.rkaushikdev.hitchline.app.SyncService.SyncOutcome;
import io.github.rkaushikdev.hitchline.app.SyncService.SyncReport;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Schemas;
import io.github.rkaushikdev.hitchline.http.Schemas.SyncBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint a handset calls when it finds signal again. It answers per item rather than for the
 * batch, so a phone with one bad row can still clear the other two hundred.
 */
@RestController
public final class SyncRoutes {

    private final SyncService sync;

    public SyncRoutes(Ports ports) {
        this.sync = new SyncService(ports);
    }

    /** Registers the sync route on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new SyncRoutes(ports));
    }

    /**
     * What became of one queued check in.
     *
     * @param localId the handset's own id for it
     * @param outcome accepted, duplicate or refused
     * @param checkInId the stored row, when there is one
     * @param message why, for a refusal
     */
    public record ItemView(String localId, SyncOutcome outcome, Optional<String> checkInId, String message) {}

    /**
     * What became of a batch.
     *
     * @param accepted how many were stored
     * @param duplicates how many had been stored before
     * @param refused how many could not be stored
     * @param results one per item, in the order sent
     */
    public record SyncResultBody(int accepted, int duplicates, int refused, List<ItemView> results) {}

    @PostMapping("/members/{memberId}/sync")
    public SyncResultBody push(
            @PathVariable("memberId") String memberId,
            @RequestBody(required = false) JsonNode payload,
            HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        SyncBody body = Schemas.parseBody(Schemas.SYNC, payload);

        List<QueuedCheckIn> queued = body.items().stream()
                .map(item -> new QueuedCheckIn(
                        item.localId(), item.habitId(), item.at(), item.reading(), item.note(), item.idempotencyKey()))
                .toList();

        SyncReport report = sync.push(member, queued);
        return new SyncResultBody(
                report.accepted(),
                report.duplicates(),
                report.refused(),
                report.results().stream()
                        .map(result ->
                                new ItemView(result.localId(), result.outcome(), result.checkInId(), result.message()))
                        .toList());
    }
}
