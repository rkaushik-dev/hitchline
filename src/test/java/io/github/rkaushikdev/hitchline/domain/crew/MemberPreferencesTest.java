package io.github.rkaushikdev.hitchline.domain.crew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MemberPreferencesTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");

    @Nested
    class Defaults {

        @Test
        void sendsADigestAtTheEndOfEachHitchInKilometres() {
            MemberPreferences preferences = MemberPreferences.defaults(ADA);
            assertThat(preferences.digest()).isEqualTo(DigestFrequency.HITCH);
            assertThat(preferences.distance()).isEqualTo(DistanceUnit.KILOMETRES);
            assertThat(preferences.quietHours()).isEmpty();
            assertThat(preferences.wantsDigest()).isTrue();
        }

        @Test
        void readsTheSameAsBuildingOneWithNothingSet() {
            assertThat(MemberPreferences.builder(ADA).build()).isEqualTo(MemberPreferences.defaults(ADA));
        }
    }

    @Nested
    class BuildingPreferences {

        @Test
        void takesAFrequencyAndAUnit() {
            MemberPreferences preferences = MemberPreferences.builder(ADA)
                    .digest("week")
                    .distance("miles")
                    .build();
            assertThat(preferences.digest()).isEqualTo(DigestFrequency.WEEK);
            assertThat(preferences.distance()).isEqualTo(DistanceUnit.MILES);
        }

        @Test
        void refusesAFrequencyOrAUnitItDoesNotKnow() {
            assertThatThrownBy(
                            () -> MemberPreferences.builder(ADA).digest("daily").build())
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() ->
                            MemberPreferences.builder(ADA).distance("furlongs").build())
                    .hasMessageContaining("kilometres, miles");
        }

        @Test
        void readsQuietHoursFromAString() {
            MemberPreferences preferences =
                    MemberPreferences.builder(ADA).quietHours("21:00-07:00").build();
            assertThat(preferences.quietHours().map(range -> range.from().hour()))
                    .hasValue(21);
        }

        @Test
        void refusesQuietHoursItCannotRead() {
            assertThatThrownBy(() -> MemberPreferences.builder(ADA)
                            .quietHours("evenings")
                            .build())
                    .hasMessageContaining("HH:MM-HH:MM");
        }

        @Test
        void checksADisplayZoneAgainstThePlatform() {
            assertThat(MemberPreferences.builder(ADA)
                            .displayZone("Europe/Oslo")
                            .build()
                            .displayZone())
                    .hasValue("Europe/Oslo");
            assertThatThrownBy(() -> MemberPreferences.builder(ADA)
                            .displayZone("North Sea/Alpha")
                            .build())
                    .hasMessageContaining("is not a timezone");
        }
    }

    @Nested
    class Digests {

        @Test
        void canBeTurnedOff() {
            assertThat(MemberPreferences.builder(ADA).digest("none").build().wantsDigest())
                    .isFalse();
        }
    }

    @Nested
    class UnitsForDisplay {

        private final MemberPreferences metric = MemberPreferences.defaults(ADA);
        private final MemberPreferences imperial =
                MemberPreferences.builder(ADA).distance("miles").build();

        @Test
        void leavesKilometresAlone() {
            assertThat(metric.forDisplay(5)).isEqualTo(5.0);
            assertThat(metric.unitLabel()).isEqualTo("km");
        }

        @Test
        void convertsForSomebodyWhoReadsMiles() {
            assertThat(imperial.forDisplay(5)).isEqualTo(3.11);
            assertThat(imperial.unitLabel()).isEqualTo("mi");
        }

        @Test
        void roundsToTwoPlacesEitherWay() {
            assertThat(metric.forDisplay(5.238)).isEqualTo(5.24);
            assertThat(imperial.forDisplay(10)).isEqualTo(6.21);
        }
    }

    @Nested
    class TheReadingZone {

        @Test
        void isTheHomeZoneUnlessSomebodyChoseAnother() {
            assertThat(MemberPreferences.defaults(ADA).readingZone("Europe/Lisbon"))
                    .isEqualTo("Europe/Lisbon");
            MemberPreferences chosen =
                    MemberPreferences.builder(ADA).displayZone("Europe/Oslo").build();
            assertThat(chosen.readingZone("Europe/Lisbon")).isEqualTo("Europe/Oslo");
        }
    }

    @Nested
    class DescribingThem {

        @Test
        void readsBackInOneLine() {
            assertThat(MemberPreferences.defaults(ADA).describe())
                    .isEqualTo("hitch digest, kilometres, quiet operator quiet hours");
            MemberPreferences own =
                    MemberPreferences.builder(ADA).quietHours("21:00-07:00").build();
            assertThat(own.describe()).contains("quiet 21:00-07:00");
        }
    }
}
