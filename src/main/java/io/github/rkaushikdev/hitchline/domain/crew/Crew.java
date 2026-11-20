package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A crew is the unit a supervisor reads: everybody working one installation on one rota.
 *
 * @param id the crew's id
 * @param name what the crew is called, 2 to 60 characters with white space squeezed
 * @param site the installation, letters, digits, spaces, dots and hyphens
 * @param siteZone the installation's clock
 */
public record Crew(String id, String name, String site, String siteZone) {

    static final int NAME_MIN = 2;
    static final int NAME_MAX = 60;

    private static final Pattern SITE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 .-]{1,39}");

    public Crew {
        Objects.requireNonNull(id, "id");
        String tidyName = Texts.squeeze(name);
        if (tidyName.length() < NAME_MIN || tidyName.length() > NAME_MAX) {
            throw ValidationException.field("name", "must be " + NAME_MIN + " to " + NAME_MAX + " characters");
        }
        name = tidyName;
        String tidySite = Texts.trim(site);
        if (!SITE.matcher(tidySite).matches()) {
            throw ValidationException.field("site", "is not a usable site name: " + site);
        }
        site = tidySite;
        siteZone = Zones.zoneId(siteZone);
    }

    public static Crew of(String id, String name, String site, String siteZone) {
        return new Crew(id, name, site, siteZone);
    }

    /** The crew with {@code crewId}, or a not found failure. */
    public static Crew require(List<Crew> crews, String crewId) {
        return crews.stream()
                .filter(entry -> entry.id.equals(crewId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("crew", crewId));
    }
}
