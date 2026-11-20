package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.ClockRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Objects;
import java.util.Optional;

/**
 * The choices a person makes about their own account. None of it changes what they are measured
 * against, which is the point: a preference that quietly moved the goalposts would make two crew
 * members incomparable.
 *
 * @param memberId whose preferences these are
 * @param digest how often they want a digest
 * @param distance the unit they read distances in
 * @param quietHours their own quiet hours, which may only widen the operator's
 * @param displayZone the zone they want reports in, when it is not their home
 */
public record MemberPreferences(
        String memberId,
        DigestFrequency digest,
        DistanceUnit distance,
        Optional<ClockRange> quietHours,
        Optional<String> displayZone) {

    private static final double KILOMETRES_TO_MILES = 0.621_371;

    public MemberPreferences {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(quietHours, "quietHours");
        Objects.requireNonNull(displayZone, "displayZone");
    }

    /** A digest at the end of each hitch, in kilometres, on the operator's quiet hours. */
    public static MemberPreferences defaults(String memberId) {
        return new MemberPreferences(
                memberId, DigestFrequency.HITCH, DistanceUnit.KILOMETRES, Optional.empty(), Optional.empty());
    }

    /** Starts building preferences from words, each checked when {@link Builder#build} runs. */
    public static Builder builder(String memberId) {
        return new Builder(memberId);
    }

    public boolean wantsDigest() {
        return digest != DigestFrequency.NONE;
    }

    /**
     * Converts a reading held in kilometres for display, never for scoring, rounded to two
     * decimal places.
     */
    public double forDisplay(double kilometres) {
        if (distance == DistanceUnit.KILOMETRES) {
            return Numbers.roundTo(kilometres, 2);
        }
        return Numbers.roundTo(kilometres * KILOMETRES_TO_MILES, 2);
    }

    public String unitLabel() {
        return distance == DistanceUnit.MILES ? "mi" : "km";
    }

    /** The zone a person wants to read reports in, which is their home by default. */
    public String readingZone(String homeZone) {
        return displayZone.orElse(homeZone);
    }

    /** One line, such as {@code hitch digest, kilometres, quiet operator quiet hours}. */
    public String describe() {
        String quiet = quietHours.map(ClockRange::toString).orElse("operator quiet hours");
        return digest + " digest, " + distance + ", quiet " + quiet;
    }

    /** Preferences written as words, as they arrive from a form or a command line. */
    public static final class Builder {

        private final String memberId;
        private String digest = DigestFrequency.HITCH.wire();
        private String distance = DistanceUnit.KILOMETRES.wire();
        private String quietHours;
        private String displayZone;

        private Builder(String memberId) {
            this.memberId = memberId;
        }

        public Builder digest(String value) {
            this.digest = value;
            return this;
        }

        public Builder distance(String value) {
            this.distance = value;
            return this;
        }

        public Builder quietHours(String value) {
            this.quietHours = value;
            return this;
        }

        public Builder displayZone(String value) {
            this.displayZone = value;
            return this;
        }

        public MemberPreferences build() {
            DigestFrequency frequency = DigestFrequency.fromWire(digest)
                    .orElseThrow(() -> ValidationException.field(
                            "digest", "must be one of " + WireValue.join(DigestFrequency.class, ", ")));
            DistanceUnit unit = DistanceUnit.fromWire(distance)
                    .orElseThrow(() -> ValidationException.field(
                            "distance", "must be one of " + WireValue.join(DistanceUnit.class, ", ")));
            if (displayZone != null && !Zones.isKnownZone(displayZone)) {
                throw ValidationException.field("displayZone", "is not a timezone: " + displayZone);
            }
            return new MemberPreferences(
                    memberId,
                    frequency,
                    unit,
                    Optional.ofNullable(quietHours).map(ClockRange::parse),
                    Optional.ofNullable(displayZone));
        }
    }
}
