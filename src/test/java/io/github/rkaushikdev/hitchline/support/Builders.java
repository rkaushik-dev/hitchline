package io.github.rkaushikdev.hitchline.support;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.UnaryOperator;

/**
 * Builders the tests share, so every suite agrees about what a valid check in, obligation and
 * result look like.
 */
public final class Builders {

    public static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    public static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    public static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    public static final String STRETCHES = Ids.habitId("hab_FEDCBA9876543210");

    private static int minted;

    private Builders() {}

    /** What a test may change about a check in. */
    public record CheckInOptions(
            Optional<String> id,
            String habit,
            OptionalDouble reading,
            String note,
            Optional<String> key,
            Optional<String> recordedAt) {

        public CheckInOptions id(String value) {
            return new CheckInOptions(Optional.of(value), habit, reading, note, key, recordedAt);
        }

        public CheckInOptions habit(String value) {
            return new CheckInOptions(id, value, reading, note, key, recordedAt);
        }

        public CheckInOptions reading(double value) {
            return new CheckInOptions(id, habit, OptionalDouble.of(value), note, key, recordedAt);
        }

        public CheckInOptions note(String value) {
            return new CheckInOptions(id, habit, reading, value, key, recordedAt);
        }

        public CheckInOptions key(String value) {
            return new CheckInOptions(id, habit, reading, note, Optional.of(value), recordedAt);
        }

        public CheckInOptions recordedAt(String value) {
            return new CheckInOptions(id, habit, reading, note, key, Optional.of(value));
        }
    }

    /** A check in for the default member that happened, and was reported, at the same moment. */
    public static CheckIn aCheckIn(String iso) {
        return aCheckIn(iso, UnaryOperator.identity());
    }

    public static CheckIn aCheckIn(String iso, UnaryOperator<CheckInOptions> change) {
        CheckInOptions options = change.apply(new CheckInOptions(
                Optional.empty(), TREADMILL, OptionalDouble.empty(), "", Optional.empty(), Optional.empty()));
        minted += 1;
        String id = options.id().orElse("chk_" + Texts.padStart(Integer.toString(minted), 16, '0'));
        return CheckIn.builder(
                        Ids.checkInId(id),
                        options.habit(),
                        ADA,
                        Zones.parseInstant(iso),
                        Zones.parseInstant(options.recordedAt().orElse(iso)),
                        CheckInSource.APP)
                .reading(options.reading())
                .note(options.note())
                .idempotencyKey(options.key())
                .build();
    }

    public static void resetCheckInIds() {
        minted = 0;
    }

    /** A day scoped obligation on one date, which is what most scoring tests want. */
    public static Obligation anObligation(String day) {
        return anObligation(day, 1, TREADMILL);
    }

    public static Obligation anObligation(String day, int times, String habit) {
        return new Obligation(habit, ADA, ObligationScope.DAY, DateRange.parse(day + ".." + day), times);
    }

    public static ObligationResult aResult(String day, AdherenceStatus status) {
        return aResult(day, status, TREADMILL);
    }

    public static ObligationResult aResult(String day, AdherenceStatus status, String habit) {
        boolean met = status == AdherenceStatus.MET;
        return new ObligationResult(
                anObligation(day, 1, habit), met ? 1 : 0, OptionalDouble.empty(), status, met ? 1 : 0);
    }

    /** A run of results, one a day from the date given. */
    public static List<ObligationResult> aRun(String from, List<AdherenceStatus> statuses) {
        CivilDate start = CivilDate.parse(from);
        List<ObligationResult> run = new ArrayList<>();
        for (int index = 0; index < statuses.size(); index += 1) {
            run.add(aResult(start.plusDays(index).toString(), statuses.get(index)));
        }
        return run;
    }
}
