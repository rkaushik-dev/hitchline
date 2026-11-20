package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import java.util.List;

/** The roster entries clerks have made. */
public interface OverrideRepository {

    List<RosterOverride> byMember(String memberId);

    void add(RosterOverride entry);
}
