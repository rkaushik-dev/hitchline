package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import java.util.List;
import java.util.Optional;

/** Crews and the dated memberships of them. */
public interface CrewRepository {

    Optional<Crew> byId(String id);

    List<Crew> all();

    void save(Crew crew);

    List<Membership> memberships();

    void addMembership(Membership entry);

    void replaceMembership(Membership entry);
}
