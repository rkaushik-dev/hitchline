package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import java.util.Optional;

/** Each member's nudge policy, when they have one of their own. */
public interface PolicyRepository {

    Optional<NudgePolicy> byMember(String memberId);

    void save(String memberId, NudgePolicy policy);
}
