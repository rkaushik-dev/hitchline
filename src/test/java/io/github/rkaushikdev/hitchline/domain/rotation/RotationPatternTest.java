package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.STANDBY;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RotationPatternTest {

    @Nested
    class ShorthandPatterns {

        @Test
        void readsAFortnightEachWay() {
            RotationPattern pattern = RotationPattern.parse("14/14");
            assertThat(pattern.cycleLength()).isEqualTo(28);
            assertThat(pattern.countRole(ONSHIFT)).isEqualTo(14);
            assertThat(pattern.countRole(OFFSHIFT)).isEqualTo(14);
        }

        @Test
        void readsAnUnevenRota() {
            RotationPattern pattern = RotationPattern.parse("21/28");
            assertThat(pattern.cycleLength()).isEqualTo(49);
            assertThat(pattern.dutyFraction()).isCloseTo(21.0 / 49, within(1e-9));
        }

        @Test
        void readsTheSwingPatternByName() {
            RotationPattern pattern = RotationPattern.parse("2-2-3");
            assertThat(pattern.cycleLength()).isEqualTo(14);
            assertThat(pattern.countRole(ONSHIFT)).isEqualTo(7);
            assertThat(pattern.cycle().subList(0, 5)).containsExactly(ONSHIFT, ONSHIFT, OFFSHIFT, OFFSHIFT, ONSHIFT);
        }
    }

    @Nested
    class NamedRotas {

        @Test
        void readsTheSwingPatternUnderEveryNameItGoesBy() {
            RotationPattern swing = RotationPattern.parse("2-2-3");
            assertThat(RotationPattern.parse("Panama")).isEqualTo(swing);
            assertThat(RotationPattern.parse("  PITMAN ")).isEqualTo(swing);
            assertThat(swing.format()).isEqualTo("2on 2off 3on 2off 2on 3off");
        }

        @Test
        void readsTheDuPontCycle() {
            RotationPattern dupont = RotationPattern.parse("DuPont");
            assertThat(dupont.cycleLength()).isEqualTo(28);
            assertThat(dupont.countRole(ONSHIFT)).isEqualTo(14);
            assertThat(dupont.cycle().subList(21, 28)).containsOnly(OFFSHIFT);
            assertThat(dupont.segments()).hasSize(8);
        }

        @Test
        void treatsSpacesInANameAsHyphens() {
            assertThat(RotationPattern.parse("equal time")).isEqualTo(RotationPattern.parse("14/14"));
            assertThat(RotationPattern.parse("Two Three")).isEqualTo(RotationPattern.parse("14/21"));
            assertThat(RotationPattern.parse("four on four off")).isEqualTo(RotationPattern.parse("4/4"));
        }

        @Test
        void saysWhichNamedRotaAPatternIs() {
            assertThat(RotationPattern.parse("21/21").knownAs()).contains("three-three");
            assertThat(RotationPattern.parse("2on 2off 3on 2off 2on 3off").knownAs())
                    .contains("panama");
            assertThat(RotationPattern.parse("10on 4off").knownAs()).isEmpty();
        }

        @Test
        void listsTheUsualNameFirst() {
            assertThat(NamedRota.PANAMA.label()).isEqualTo("panama");
            assertThat(NamedRota.PANAMA.names()).contains("2-2-3", "pitman");
            assertThat(NamedRota.named("nobody's rota")).isEmpty();
        }

        @Test
        void givesEveryNamedRotaADistinctCycle() {
            assertThat(Arrays.stream(NamedRota.values()).map(NamedRota::pattern).distinct())
                    .hasSize(NamedRota.values().length);
        }
    }

    @Nested
    class ExplicitSegments {

        @Test
        void readsARunOfSegmentsWithTravelDays() {
            RotationPattern pattern = RotationPattern.parse("1travel 13on 1travel 13off");
            assertThat(pattern.cycleLength()).isEqualTo(28);
            assertThat(pattern.countRole(TRAVEL)).isEqualTo(2);
            assertThat(pattern.cycle().get(0)).isEqualTo(TRAVEL);
            assertThat(pattern.cycle().get(1)).isEqualTo(ONSHIFT);
        }

        @Test
        void acceptsCommasAsWellAsSpaces() {
            assertThat(RotationPattern.parse("7on,7off").cycleLength()).isEqualTo(14);
        }

        @Test
        void acceptsTheLongRoleWords() {
            assertThat(RotationPattern.parse("5onshift 2offshift 3standby").countRole(STANDBY))
                    .isEqualTo(3);
        }

        @Test
        void isNotCaseSensitive() {
            assertThat(RotationPattern.parse("14ON 14OFF").cycleLength()).isEqualTo(28);
        }

        @Test
        void collapsesNeighbouringSegmentsOfTheSameRole() {
            assertThat(RotationPattern.parse("7on 7on 14off").segments())
                    .containsExactly(new RotationSegment(ONSHIFT, 14), new RotationSegment(OFFSHIFT, 14));
        }
    }

    @Nested
    class Rejections {

        @Test
        void refusesAnEmptyPattern() {
            assertThatThrownBy(() -> RotationPattern.parse("   ")).hasMessageContaining("is empty");
        }

        @Test
        void refusesAnUnknownRoleWord() {
            assertThatThrownBy(() -> RotationPattern.parse("14on 14ashore"))
                    .hasMessageContaining("does not know the day role");
        }

        @Test
        void refusesASegmentItCannotParse() {
            assertThatThrownBy(() -> RotationPattern.parse("two weeks on"))
                    .hasMessageContaining("cannot read the segment");
        }

        @Test
        void refusesAPatternWithNoOnShiftDays() {
            assertThatThrownBy(() -> RotationPattern.parse("14off")).hasMessageContaining("no on shift days");
        }

        @Test
        void refusesAZeroLengthSegment() {
            assertThatThrownBy(() -> RotationPattern.of(new RotationSegment(ONSHIFT, 0)))
                    .hasMessageContaining("segment of 0 days");
        }

        @Test
        void refusesAnEmptySegmentList() {
            assertThatThrownBy(() -> RotationPattern.of(List.of())).hasMessageContaining("at least one segment");
        }

        @Test
        void refusesACycleLongerThanAYear() {
            assertThatThrownBy(() -> RotationPattern.parse("200on 200off")).hasMessageContaining("longer than a year");
        }
    }

    @Nested
    class Formatting {

        @Test
        void writesACanonicalForm() {
            assertThat(RotationPattern.parse("14/14").format()).isEqualTo("14on 14off");
            assertThat(RotationPattern.parse("1travel 13on 1travel 13off").format())
                    .isEqualTo("1travel 13on 1travel 13off");
        }

        @Test
        void roundTripsThroughTheParser() {
            RotationPattern written = RotationPattern.parse("2standby 5on 2off");
            RotationPattern reread = RotationPattern.parse(written.format());
            assertThat(reread).isEqualTo(written);
            assertThat(reread.cycle()).isEqualTo(written.cycle());
        }
    }

    @Nested
    class RoleAtIndex {

        private final RotationPattern pattern = RotationPattern.parse("14/14");

        @Test
        void readsInsideTheFirstCycle() {
            assertThat(pattern.roleAtIndex(0)).isEqualTo(ONSHIFT);
            assertThat(pattern.roleAtIndex(13)).isEqualTo(ONSHIFT);
            assertThat(pattern.roleAtIndex(14)).isEqualTo(OFFSHIFT);
        }

        @Test
        void wrapsForwards() {
            assertThat(pattern.roleAtIndex(28)).isEqualTo(ONSHIFT);
            assertThat(pattern.roleAtIndex(42)).isEqualTo(OFFSHIFT);
        }

        @Test
        void wrapsBackwards() {
            assertThat(pattern.roleAtIndex(-1)).isEqualTo(OFFSHIFT);
            assertThat(pattern.roleAtIndex(-14)).isEqualTo(OFFSHIFT);
            assertThat(pattern.roleAtIndex(-15)).isEqualTo(ONSHIFT);
        }
    }

    @Nested
    class IsDayRole {

        @Test
        void recognisesTheFourRoles() {
            assertThat(DayRole.isDayRole("onshift")).isTrue();
            assertThat(DayRole.isDayRole("standby")).isTrue();
            assertThat(DayRole.isDayRole("holiday")).isFalse();
        }
    }
}
