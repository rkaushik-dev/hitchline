package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.List;

/**
 * One question about who may read what: may this viewer read this habit of the person whose
 * preference it is, given the crews everybody was in on that day.
 *
 * @param viewer who is asking
 * @param preference the sharing settings of the person being read
 * @param habitId the habit
 * @param memberships every crew membership that might apply
 * @param on the day asked about
 */
public record AccessRequest(
        Viewer viewer, SharingPreference preference, String habitId, List<Membership> memberships, CivilDate on) {

    public AccessRequest {
        memberships = List.copyOf(memberships);
    }
}
