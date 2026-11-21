package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePlanner;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.List;

/**
 * Planning the pings. The service reads the member through the same loader the adherence report
 * uses, so a nudge can never claim something is owed that the report does not also show as open.
 */
public final class NudgesService {

    private final Ports ports;
    private final MemberLoader loader;

    public NudgesService(Ports ports) {
        this.ports = ports;
        this.loader = new MemberLoader(ports);
    }

    /** The member's own policy, or the default one. */
    public NudgePolicy policyFor(String memberId) {
        return ports.policies().byMember(memberId).orElse(NudgePolicy.DEFAULT_POLICY);
    }

    /** Builds and stores a policy for the member from the parts given. */
    public NudgePolicy setPolicy(String memberId, NudgePolicy.Builder input) {
        if (ports.members().byId(memberId).isEmpty()) {
            throw new NotFoundException("crew member", memberId);
        }
        NudgePolicy policy = input.build();
        ports.policies().save(memberId, policy);
        return policy;
    }

    public List<PlannedNudge> plan(String memberId, String window) {
        LoadedMember loaded = loader.load(memberId);
        List<ObligationResult> results = loader.score(loaded, DateRange.parse(window));
        return NudgePlanner.plan(
                loaded.roster(), policyFor(memberId), results, ports.clock().now());
    }

    /** True when the member has a run going and something open that has not been started. */
    public boolean atRisk(String memberId, String window) {
        LoadedMember loaded = loader.load(memberId);
        List<ObligationResult> results = loader.score(loaded, DateRange.parse(window));
        return StreakSummary.compute(results, loaded.shields(), memberId).isAtRisk(results);
    }
}
