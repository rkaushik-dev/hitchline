package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import java.util.List;
import java.util.Optional;

/** Crew members, by id. Saving a member with an id already held replaces it. */
public interface MemberRepository {

    Optional<CrewMember> byId(String id);

    List<CrewMember> all();

    void save(CrewMember member);
}
