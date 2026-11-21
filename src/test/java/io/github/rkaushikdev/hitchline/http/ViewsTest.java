package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgeReason;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.http.Views.CheckInView;
import io.github.rkaushikdev.hitchline.http.Views.HabitView;
import io.github.rkaushikdev.hitchline.http.Views.MemberView;
import io.github.rkaushikdev.hitchline.http.Views.ResolvedCheckInView;
import io.github.rkaushikdev.hitchline.http.Views.ResultView;
import io.github.rkaushikdev.hitchline.http.Views.StreakView;
import io.github.rkaushikdev.hitchline.http.Views.SummaryView;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.Arrays;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ViewsTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    private static CheckIn.Builder anEntry() {
        return CheckIn.builder(
                Ids.checkInId("chk_0123456789ABCDEF"),
                TREADMILL,
                ADA,
                Zones.parseInstant("2025-01-10T07:30:00Z"),
                Zones.parseInstant("2025-01-10T07:31:00Z"),
                CheckInSource.WATCH);
    }

    @Nested
    class TheHabitView {

        @Test
        void formatsTheCadenceAndLeavesAnAbsentTargetNull() {
            HabitView view = Views.habitView(Fixtures.aHabit());
            assertThat(view.cadence()).isEqualTo("1/onshift-day");
            assertThat(view.target()).isEmpty();
            assertThat(view.endsOn()).isEmpty();
            assertThat(Json.stringify(ApiJson.toTree(view))).contains("\"target\":null", "\"endsOn\":null");
        }

        @Test
        void formatsATargetAndAnEndDateWhenSet() {
            HabitView view = Views.habitView(
                    Fixtures.aHabit(o -> o.target("at least 20 minutes").endsOn("2025-06-30")));
            assertThat(view.target()).hasValue("at least 20 minutes");
            assertThat(view.endsOn()).hasValue("2025-06-30");
        }
    }

    @Nested
    class TheMemberView {

        @Test
        void writesTheRotationAsAPatternAndAnAnchor() {
            MemberView view = Views.memberView(Fixtures.aMember());
            assertThat(view.pattern()).isEqualTo("14on 14off");
            assertThat(view.anchor()).isEqualTo("2025-01-06");
            assertThat(view.phase()).isZero();
        }
    }

    @Nested
    class CheckInViews {

        private final CheckIn entry = anEntry().reading(22).build();

        @Test
        void carriesNoRotationDayUntilItIsResolved() {
            CheckInView view = Views.checkInView(entry);
            assertThat(Arrays.stream(CheckInView.class.getRecordComponents()).map(component -> component.getName()))
                    .doesNotContain("onDate", "zone");
            assertThat(view.reading()).hasValue(22);
        }

        @Test
        void carriesTheRotationDayOnceResolved() {
            ResolvedCheckInView view = Views.resolvedView(ResolvedCheckIn.resolve(Fixtures.aMember(), entry));
            assertThat(view.onDate()).isEqualTo("2025-01-10");
            assertThat(view.zone()).isEqualTo("Europe/Oslo");
        }

        @Test
        void writesAnAbsentReadingAsNull() {
            CheckInView view = Views.checkInView(anEntry().build());
            assertThat(view.reading()).isEqualTo(OptionalDouble.empty());
            assertThat(ApiJson.toTree(view).get("reading").isNull()).isTrue();
        }
    }

    @Nested
    class ResultAndSummaryViews {

        private final ObligationResult result = new ObligationResult(
                new Obligation(TREADMILL, ADA, ObligationScope.HITCH, r("2025-01-06..2025-02-02"), 3),
                2,
                OptionalDouble.of(40),
                AdherenceStatus.PARTIAL,
                2.0 / 3);

        @Test
        void roundsProgressToTwoPlaces() {
            assertThat(Views.resultView(result).progress()).isEqualTo(0.67);
        }

        @Test
        void writesTheWindowAndTheDueDay() {
            ResultView view = Views.resultView(result);
            assertThat(view.window()).isEqualTo("2025-01-06..2025-02-02");
            assertThat(view.dueOn()).isEqualTo("2025-02-02");
            assertThat(view.scope()).isEqualTo("hitch");
        }

        @Test
        void roundsASummaryRate() {
            SummaryView view =
                    Views.summaryView(new PeriodSummary(r("2025-01-06..2025-01-19"), 3, 1, 1, 1, 0, 0, 1.0 / 3));
            assertThat(view.rate()).isEqualTo(0.33);
            assertThat(view.window()).isEqualTo("2025-01-06..2025-01-19");
        }

        @Test
        void writesAnEmptyStreakWithNulls() {
            StreakView view = Views.streakView(StreakSummary.NO_STREAK);
            assertThat(ApiJson.toTree(view))
                    .isEqualTo(Json.parse(
                            "{\"current\":0,\"longest\":0,\"shielded\":0,\"lastMetOn\":null,\"brokenOn\":null}"));
        }
    }

    @Nested
    class RosterViews {

        @Test
        void writesAnOverride() {
            Views.OverrideView view = Views.overrideView(RosterOverride.of(
                    ADA,
                    r("2025-01-20..2025-01-22"),
                    DayRole.ONSHIFT,
                    OverrideReason.HELD_OVER,
                    Zones.parseInstant("2025-01-18T10:00:00Z")));
            assertThat(view.range()).isEqualTo("2025-01-20..2025-01-22");
            assertThat(view.reason()).isEqualTo("held_over");
        }

        @Test
        void writesAShieldWithANullGranterWhenSelfDeclared() {
            Views.ShieldView view = Views.shieldView(Shield.of(
                    ADA,
                    r("2025-01-20..2025-01-26"),
                    ShieldReason.ANNUAL_LEAVE,
                    Zones.parseInstant("2025-01-18T10:00:00Z")));
            assertThat(view.grantedBy()).isEmpty();
            assertThat(ApiJson.toTree(view).get("grantedBy").isNull()).isTrue();
        }
    }

    @Nested
    class CrewViews {

        @Test
        void writesACrewAndAMembership() {
            Crew alpha = Crew.of(Ids.crewId("crw_0123456789ABCDEF"), "Alpha production", "Grane B", "Europe/Oslo");
            assertThat(Views.crewView(alpha).site()).isEqualTo("Grane B");
            Membership entry = Membership.of(alpha.id(), ADA, CivilDate.parse("2025-01-01"));
            assertThat(Views.membershipView(entry).to()).isEmpty();
        }
    }

    @Nested
    class TheNudgeView {

        @Test
        void writesTheInstantAndTheDay() {
            Views.NudgeView view = Views.nudgeView(new PlannedNudge(
                    TREADMILL,
                    "hab_0123456789ABCDEF:day:2025-01-10",
                    Zones.parseInstant("2025-01-10T19:00:00Z"),
                    CivilDate.parse("2025-01-10"),
                    NudgeReason.NOTHING_YET));
            assertThat(view.at()).isEqualTo("2025-01-10T19:00:00.000Z");
            assertThat(view.onDate()).isEqualTo("2025-01-10");
        }
    }
}
