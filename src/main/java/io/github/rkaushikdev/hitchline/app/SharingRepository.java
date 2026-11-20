package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import java.util.Optional;

/** Each member's sharing settings, when they have made any. */
public interface SharingRepository {

    Optional<SharingPreference> byMember(String memberId);

    void save(SharingPreference preference);
}
