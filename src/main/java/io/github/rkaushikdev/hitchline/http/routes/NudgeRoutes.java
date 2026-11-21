package io.github.rkaushikdev.hitchline.http.routes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.NudgesService;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgeChannel;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.http.Schemas;
import io.github.rkaushikdev.hitchline.http.Schemas.PolicyBody;
import io.github.rkaushikdev.hitchline.http.Views;
import io.github.rkaushikdev.hitchline.http.Views.NudgeView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nudge routes. The plan is a read: nothing is sent from here, a worker picks the plan up and
 * delivers it, and this endpoint is what it asks.
 */
@RestController
public final class NudgeRoutes {

    private final NudgesService nudges;

    public NudgeRoutes(Ports ports) {
        this.nudges = new NudgesService(ports);
    }

    /** Registers the nudge routes on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new NudgeRoutes(ports));
    }

    /**
     * What the planner would send.
     *
     * @param nudges in the order they would go
     */
    public record NudgesBody(List<NudgeView> nudges) {}

    /**
     * A nudge policy.
     *
     * @param quietHours {@code HH:MM-HH:MM}
     * @param minGapMinutes the least time between two nudges
     * @param maxPerDay the most nudges in a day
     * @param leadMinutes how long before a deadline to nudge
     * @param roles the day roles nudges are sent on
     * @param channel how they are sent
     */
    public record PolicyView(
            String quietHours,
            int minGapMinutes,
            int maxPerDay,
            int leadMinutes,
            List<DayRole> roles,
            NudgeChannel channel) {}

    private static PolicyView policyView(NudgePolicy policy) {
        return new PolicyView(
                policy.quietHours().toString(),
                policy.minGapMinutes(),
                policy.maxPerDay(),
                policy.leadMinutes(),
                policy.roles(),
                policy.channel());
    }

    @GetMapping("/members/{memberId}/nudges")
    public NudgesBody plan(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        return new NudgesBody(nudges.plan(member, Query.windowStringOf(request, "plan nudges")).stream()
                .map(Views::nudgeView)
                .toList());
    }

    @GetMapping("/members/{memberId}/nudge-policy")
    public PolicyView policy(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        return policyView(nudges.policyFor(member));
    }

    @PutMapping("/members/{memberId}/nudge-policy")
    public PolicyView setPolicy(
            @PathVariable("memberId") String memberId,
            @RequestBody(required = false) JsonNode payload,
            HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        Auth.requireScope(request, Scope.WRITE);
        PolicyBody body = Schemas.parseBody(Schemas.POLICY, payload);
        NudgePolicy.Builder input = NudgePolicy.builder();
        body.quietHours().ifPresent(input::quietHours);
        body.minGapMinutes().ifPresent(input::minGapMinutes);
        body.maxPerDay().ifPresent(input::maxPerDay);
        body.leadMinutes().ifPresent(input::leadMinutes);
        body.roles()
                .ifPresent(
                        roles -> input.roles(roles.stream().map(DayRole::wire).toList()));
        body.channel().ifPresent(input::channel);
        return policyView(nudges.setPolicy(member, input));
    }
}
