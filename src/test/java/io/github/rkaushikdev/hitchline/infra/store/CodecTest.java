package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.MemberStatus;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CodecTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String ALPHA = Ids.crewId("crw_0123456789ABCDEF");

    @Nested
    class Members {

        @Test
        void roundTrips() {
            CrewMember member = Fixtures.aMember();
            assertThat(Codec.decodeMember(Codec.encodeMember(member))).isEqualTo(member);
        }

        @Test
        void keepsTheRotationAsAReadablePattern() {
            assertThat(Codec.encodeMember(Fixtures.aMember()).get("pattern").textValue())
                    .isEqualTo("14on 14off");
        }

        @Test
        void keepsASuspendedStatus() {
            CrewMember suspended = Fixtures.aMember().moveTo(MemberStatus.SUSPENDED);
            assertThat(Codec.decodeMember(Codec.encodeMember(suspended)).status())
                    .isEqualTo(MemberStatus.SUSPENDED);
        }

        @Test
        void refusesARowWithAMissingField() {
            ObjectNode row = Codec.encodeMember(Fixtures.aMember());
            row.remove("siteZone");
            assertThatThrownBy(() -> Codec.decodeMember(row)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesARowWithAFieldOfTheWrongType() {
            ObjectNode row = Codec.encodeMember(Fixtures.aMember()).put("phase", "first");
            assertThatThrownBy(() -> Codec.decodeMember(row)).hasMessageContaining("must be a number");
        }
    }

    @Nested
    class Habits {

        @Test
        void roundTripsWithoutATarget() {
            Habit entry = Fixtures.aHabit();
            assertThat(Codec.decodeHabit(Codec.encodeHabit(entry))).isEqualTo(entry);
        }

        @Test
        void roundTripsWithATargetAndAnEndDate() {
            Habit entry = Fixtures.aHabit(
                    o -> o.target("at least 20 minutes").endsOn("2025-06-30").tags("fitness"));
            Habit back = Codec.decodeHabit(Codec.encodeHabit(entry));
            assertThat(back.target().map(HabitTarget::amount)).hasValue(20.0);
            assertThat(back.endsOn().map(CivilDate::month)).hasValue(6);
            assertThat(back.tags()).containsExactly("fitness");
        }

        @Test
        void writesAnAbsentTargetAsNull() {
            assertThat(Codec.encodeHabit(Fixtures.aHabit()).get("target").isNull())
                    .isTrue();
        }

        @Test
        void refusesATagListThatIsNotStrings() {
            ObjectNode row = Codec.encodeHabit(Fixtures.aHabit());
            row.putArray("tags").add(1).add(2);
            assertThatThrownBy(() -> Codec.decodeHabit(row)).hasMessageContaining("list of strings");
        }
    }

    @Nested
    class CheckIns {

        private final CheckIn entry = CheckIn.builder(
                        Ids.checkInId("chk_0123456789ABCDEF"),
                        TREADMILL,
                        ADA,
                        Zones.parseInstant("2025-01-10T07:30:00Z"),
                        Zones.parseInstant("2025-01-10T07:31:00Z"),
                        CheckInSource.WATCH)
                .reading(22)
                .note("before the shift")
                .idempotencyKey("watch-2025-01-10")
                .build();

        @Test
        void roundTrips() {
            assertThat(Codec.decodeCheckIn(Codec.encodeCheckIn(entry))).isEqualTo(entry);
        }

        @Test
        void keepsAReadingOfZeroRatherThanLosingIt() {
            CheckIn zero = entry.withReading(OptionalDouble.of(0));
            assertThat(Codec.decodeCheckIn(Codec.encodeCheckIn(zero)).reading()).hasValue(0);
        }

        @Test
        void roundTripsAVoidedEntry() {
            CheckIn voided = entry.voidAt(Zones.parseInstant("2025-01-11T09:00:00Z"));
            assertThat(Codec.decodeCheckIn(Codec.encodeCheckIn(voided)).voidedAt())
                    .hasValue(Zones.parseInstant("2025-01-11T09:00:00Z"));
        }
    }

    @Nested
    class OverridesAndShields {

        @Test
        void roundTripsAnOverride() {
            RosterOverride entry = new RosterOverride(
                    ADA,
                    DateRange.parse("2025-01-20..2025-01-22"),
                    DayRole.ONSHIFT,
                    OverrideReason.HELD_OVER,
                    "relief weathered off",
                    Zones.parseInstant("2025-01-18T10:00:00Z"));
            assertThat(Codec.decodeOverride(Codec.encodeOverride(entry))).isEqualTo(entry);
        }

        @Test
        void roundTripsAShieldWithAGranter() {
            Shield entry = new Shield(
                    ADA,
                    DateRange.parse("2025-02-03..2025-02-07"),
                    ShieldReason.TRAINING,
                    "",
                    Optional.of(BO),
                    Zones.parseInstant("2025-01-18T10:00:00Z"));
            assertThat(Codec.decodeShield(Codec.encodeShield(entry))).isEqualTo(entry);
        }

        @Test
        void roundTripsASelfDeclaredShield() {
            Shield entry = Shield.of(
                    ADA,
                    DateRange.parse("2025-02-03..2025-02-07"),
                    ShieldReason.SICK,
                    Zones.parseInstant("2025-01-18T10:00:00Z"));
            assertThat(Codec.decodeShield(Codec.encodeShield(entry)).grantedBy())
                    .isEmpty();
        }
    }

    @Nested
    class Crews {

        @Test
        void roundTripsACrew() {
            Crew entry = Crew.of(ALPHA, "Alpha production", "Grane B", "Europe/Oslo");
            assertThat(Codec.decodeCrew(Codec.encodeCrew(entry))).isEqualTo(entry);
        }

        @Test
        void roundTripsAnOpenEndedMembership() {
            Membership entry = Membership.of(ALPHA, ADA, CivilDate.parse("2025-01-01"));
            assertThat(Codec.decodeMembership(Codec.encodeMembership(entry))).isEqualTo(entry);
        }

        @Test
        void roundTripsAClosedMembership() {
            Membership entry = Membership.of(
                    ALPHA, ADA, CivilDate.parse("2025-01-01"), CrewRole.LEAD, CivilDate.parse("2025-06-30"));
            Membership back = Codec.decodeMembership(Codec.encodeMembership(entry));
            assertThat(back.to().map(CivilDate::month)).hasValue(6);
            assertThat(back.role()).isEqualTo(CrewRole.LEAD);
        }
    }

    @Nested
    class SharingPreferences {

        @Test
        void roundTripsTheFallbackAndThePerHabitMap() {
            SharingPreference preference =
                    SharingPreference.of(ADA, Visibility.LEAD_ONLY).withHabitVisibility(TREADMILL, Visibility.CREW);
            SharingPreference back = Codec.decodeSharing(Codec.encodeSharing(preference));
            assertThat(back.fallback()).isEqualTo(Visibility.LEAD_ONLY);
            assertThat(back.perHabit()).containsEntry(TREADMILL, Visibility.CREW);
        }

        @Test
        void refusesAMalformedPair() {
            ObjectNode row = Codec.encodeSharing(SharingPreference.of(ADA));
            row.set("perHabit", Json.array().add("nonsense"));
            assertThatThrownBy(() -> Codec.decodeSharing(row)).hasMessageContaining("is not a pair");
        }
    }
}
