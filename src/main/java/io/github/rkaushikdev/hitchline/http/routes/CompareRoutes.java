package io.github.rkaushikdev.hitchline.http.routes;

import io.github.rkaushikdev.hitchline.app.CompareService;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewComparison;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Principal;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.util.Numbers;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The crew comparison. It needs the read scope and nothing more, because the service already drops
 * anything the viewer is not allowed to see and says how much it dropped.
 */
@RestController
public final class CompareRoutes {

    private final CompareService compare;

    public CompareRoutes(Ports ports) {
        this.compare = new CompareService(ports);
    }

    /** Registers the comparison route on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new CompareRoutes(ports));
    }

    /**
     * One member's line.
     *
     * @param memberId who
     * @param displayName their name
     * @param owed obligations due
     * @param met obligations met
     * @param missed obligations missed
     * @param rate the share met, to two places
     * @param hidden habits left out because the viewer may not read them
     */
    public record LineView(
            String memberId, String displayName, int owed, int met, int missed, double rate, int hidden) {}

    /**
     * A crew side by side.
     *
     * @param crewId the crew
     * @param rate everything met over everything owed, to two places
     * @param lines one per member, best rate first
     */
    public record ComparisonBody(String crewId, double rate, List<LineView> lines) {}

    @GetMapping("/crews/{crewId}/comparison")
    public ComparisonBody comparison(@PathVariable("crewId") String crewId, HttpServletRequest request) {
        Principal principal = Auth.requireScope(request, Scope.READ);
        String window = Query.stringOf(request, "window")
                .orElseThrow(() -> ValidationException.field("window", "is needed to compare a crew"));
        String on = Query.stringOf(request, "on")
                .orElseThrow(() -> ValidationException.field("on", "is needed to read the crew as it was that day"));
        CrewComparison report = compare.crew(principal.memberId(), Ids.crewId(crewId), window, on);
        return new ComparisonBody(
                report.crewId(),
                Numbers.roundTo(report.rate(), 2),
                report.lines().stream()
                        .map(line -> new LineView(
                                line.memberId(),
                                line.displayName(),
                                line.owed(),
                                line.met(),
                                line.missed(),
                                Numbers.roundTo(line.rate(), 2),
                                line.hidden()))
                        .toList());
    }
}
