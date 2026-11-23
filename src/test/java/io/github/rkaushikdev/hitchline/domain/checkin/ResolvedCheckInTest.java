package io.github.rkaushikdev.hitchline.domain.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResolvedCheckInTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static int next;

    private static CheckIn at(String iso) {
        return at(iso, OptionalDouble.empty());
    }

    private static CheckIn at(String iso, double reading) {
        return at(iso, OptionalDouble.of(reading));
    }

    private static CheckIn at(String iso, OptionalDouble reading) {
        next += 1;
        return CheckIn.builder(
                        Ids.checkInId("chk_" + Texts.padStart(Integer.toString(next), 16, '0')),
                        Ids.habitId("hab_0123456789ABCDEF"),
                        Ids.crewMemberId("mem_0123456789ABCDEF"),
                        Zones.parseInstant(iso),
                        Zones.parseInstant(iso),
                        CheckInSource.APP)
                .reading(reading)
                .build();
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    @Nested
    class ResolvingOneCheckIn {

        @Test
        void putsAnOnShiftEntryOnTheSiteDay() {
            ResolvedCheckIn resolved = ResolvedCheckIn.resolve(MEMBER, at("2025-01-10T09:00:00Z"));
            assertThat(resolved.day().date().toString()).isEqualTo("2025-01-10");
            assertThat(resolved.day().zone()).isEqualTo("Europe/Oslo");
        }

        @Test
        void putsAnOffShiftEntryOnTheHomeDay() {
            ResolvedCheckIn resolved = ResolvedCheckIn.resolve(MEMBER, at("2025-01-25T23:30:00Z"));
            assertThat(resolved.day().date().toString()).isEqualTo("2025-01-25");
            assertThat(resolved.day().zone()).isEqualTo("Europe/Lisbon");
        }

        @Test
        void keepsTheCheckInAlongsideTheDay() {
            CheckIn entry = at("2025-01-10T09:00:00Z", 25);
            assertThat(ResolvedCheckIn.resolve(MEMBER, entry).checkIn()).isSameAs(entry);
        }
    }

    @Nested
    class RepeatTaps {

        private List<ResolvedCheckIn> resolve(CheckIn... entries) {
            return ResolvedCheckIn.resolveAll(MEMBER, List.of(entries));
        }

        @Test
        void countsTheSameTapTwiceInsideTwoMinutesOnce() {
            CheckIn first = at("2025-01-08T07:00:00Z");
            CheckIn again = at("2025-01-08T07:01:30Z");
            List<ResolvedCheckIn> resolved = resolve(again, first);
            assertThat(ResolvedCheckIn.repeatTaps(resolved)).containsExactly(again.id());
            assertThat(ResolvedCheckIn.withoutRepeatTaps(resolved))
                    .extracting(ResolvedCheckIn::checkIn)
                    .containsExactly(first);
        }

        @Test
        void keepsATapMoreThanTwoMinutesLater() {
            List<ResolvedCheckIn> resolved = resolve(at("2025-01-08T07:00:00Z"), at("2025-01-08T07:02:01Z"));
            assertThat(ResolvedCheckIn.repeatTaps(resolved)).isEmpty();
        }

        @Test
        void keepsTwoReadingsThatDiffer() {
            List<ResolvedCheckIn> resolved = resolve(at("2025-01-08T07:00:00Z", 6), at("2025-01-08T07:00:30Z", 2));
            assertThat(ResolvedCheckIn.withoutRepeatTaps(resolved)).hasSize(2);
        }

        @Test
        void measuresFromTheTapCountedSoAStreamOfRepeatsDoesNotCreep() {
            CheckIn first = at("2025-01-08T07:00:00Z", 8);
            CheckIn second = at("2025-01-08T07:01:00Z", 8);
            CheckIn third = at("2025-01-08T07:02:00Z", 8);
            CheckIn fourth = at("2025-01-08T07:03:00Z", 8);
            assertThat(ResolvedCheckIn.repeatTaps(resolve(first, second, third, fourth)))
                    .containsExactlyInAnyOrder(second.id(), third.id());
        }

        @Test
        void keepsTapsOnTwoHabitsApart() {
            CheckIn treadmill = at("2025-01-08T07:00:00Z");
            CheckIn stretches = CheckIn.builder(
                            Ids.checkInId("chk_FFFFFFFFFFFFFFFF"),
                            Ids.habitId("hab_FEDCBA9876543210"),
                            treadmill.memberId(),
                            treadmill.at(),
                            treadmill.at(),
                            CheckInSource.WATCH)
                    .build();
            assertThat(ResolvedCheckIn.repeatTaps(resolve(treadmill, stretches)))
                    .isEmpty();
        }
    }

    @Nested
    class Grouping {

        private final List<ResolvedCheckIn> resolved = ResolvedCheckIn.resolveAll(
                MEMBER,
                List.of(at("2025-01-10T06:00:00Z", 20), at("2025-01-10T18:00:00Z", 30), at("2025-01-12T06:00:00Z")));

        @Test
        void collectsEntriesByTheDayTheyLandedOn() {
            Map<String, List<ResolvedCheckIn>> groups = ResolvedCheckIn.groupByDay(resolved);
            assertThat(groups.get("2025-01-10")).hasSize(2);
            assertThat(groups.get("2025-01-12")).hasSize(1);
            assertThat(groups).doesNotContainKey("2025-01-11");
        }

        @Test
        void listsTheDaysInOrder() {
            assertThat(ResolvedCheckIn.daysTouched(resolved)).containsExactly("2025-01-10", "2025-01-12");
        }

        @Test
        void filtersToOneDate() {
            assertThat(ResolvedCheckIn.onDate(resolved, CivilDate.parse("2025-01-10")))
                    .hasSize(2);
            assertThat(ResolvedCheckIn.onDate(resolved, CivilDate.parse("2025-01-11")))
                    .isEmpty();
        }
    }

    @Nested
    class Windows {

        private final List<ResolvedCheckIn> resolved = ResolvedCheckIn.resolveAll(
                MEMBER,
                List.of(
                        at("2025-01-10T06:00:00Z", 20),
                        at("2025-01-12T06:00:00Z", 30),
                        at("2025-01-25T12:00:00Z", 40)));

        @Test
        void countsWhatFallsInside() {
            assertThat(ResolvedCheckIn.countInRange(resolved, r("2025-01-06..2025-01-19")))
                    .isEqualTo(2);
            assertThat(ResolvedCheckIn.countInRange(resolved, r("2025-01-20..2025-02-02")))
                    .isEqualTo(1);
        }

        @Test
        void isClosedAtBothEnds() {
            assertThat(ResolvedCheckIn.countInRange(resolved, r("2025-01-10..2025-01-12")))
                    .isEqualTo(2);
            assertThat(ResolvedCheckIn.countInRange(resolved, r("2025-01-11..2025-01-11")))
                    .isZero();
        }

        @Test
        void returnsTheEntriesThemselves() {
            List<ResolvedCheckIn> found = ResolvedCheckIn.inRange(resolved, r("2025-01-10..2025-01-10"));
            assertThat(found.get(0).checkIn().reading()).hasValue(20);
        }

        @Test
        void collectsReadingsAndSkipsEntriesWithoutOne() {
            List<ResolvedCheckIn> mixed = ResolvedCheckIn.resolveAll(
                    MEMBER, List.of(at("2025-01-10T06:00:00Z", 20), at("2025-01-10T18:00:00Z")));
            assertThat(ResolvedCheckIn.readingsInRange(mixed, r("2025-01-10..2025-01-10")))
                    .containsExactly(20.0);
        }
    }

    @Nested
    class StraddledDays {

        @Test
        void picksOutTheEntriesTheTwoClocksDisagreedAbout() {
            List<ResolvedCheckIn> resolved = ResolvedCheckIn.resolveAll(
                    MEMBER,
                    List.of(at("2025-01-10T09:00:00Z"), at("2025-01-25T23:30:00Z"), at("2025-01-19T23:30:00Z")));
            List<ResolvedCheckIn> odd = ResolvedCheckIn.straddling(resolved);
            assertThat(odd).hasSize(2);
            assertThat(odd.stream().map(item -> item.day().date().toString()).sorted())
                    .containsExactly("2025-01-20", "2025-01-25");
        }

        @Test
        void findsNoneWhenHomeAndSiteShareAClock() {
            CrewMember local = Fixtures.aMember(o -> o.homeZone("Europe/Oslo").siteZone("Europe/Oslo"));
            List<ResolvedCheckIn> resolved = ResolvedCheckIn.resolveAll(local, List.of(at("2025-01-25T23:30:00Z")));
            assertThat(ResolvedCheckIn.straddling(resolved)).isEmpty();
        }
    }
}
