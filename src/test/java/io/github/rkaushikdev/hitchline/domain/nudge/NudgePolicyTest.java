package io.github.rkaushikdev.hitchline.domain.nudge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NudgePolicyTest {

    private static final NudgePolicy DEFAULT = NudgePolicy.DEFAULT_POLICY;

    private static ClockTime t(String text) {
        return ClockTime.parse(text);
    }

    @Nested
    class TheDefaultPolicy {

        @Test
        void isQuietOvernightAndPingsThreeTimesADay() {
            assertThat(DEFAULT.maxPerDay()).isEqualTo(3);
            assertThat(DEFAULT.describe()).isEqualTo("3 a day by push, quiet 22:00-06:00 overnight");
        }

        @Test
        void doesNotNudgeOnATravelDay() {
            assertThat(DEFAULT.nudgesOnRole(DayRole.TRAVEL)).isFalse();
            assertThat(DEFAULT.nudgesOnRole(DayRole.ONSHIFT)).isTrue();
        }
    }

    @Nested
    class BuildingAPolicy {

        @Test
        void takesTheDefaultsWhenGivenNothing() {
            assertThat(NudgePolicy.builder().build()).isEqualTo(DEFAULT);
        }

        @Test
        void readsQuietHoursFromAString() {
            assertThat(NudgePolicy.builder()
                            .quietHours("23:00-05:30")
                            .build()
                            .quietHours()
                            .to()
                            .toString())
                    .isEqualTo("05:30");
        }

        @Test
        void boundsTheNumbers() {
            assertThatThrownBy(() -> NudgePolicy.builder().minGapMinutes(0).build())
                    .hasMessageContaining("between 1 and 1440");
            assertThatThrownBy(() -> NudgePolicy.builder().maxPerDay(13).build())
                    .hasMessageContaining("between 0 and 12");
            assertThatThrownBy(() -> NudgePolicy.builder().leadMinutes(-1).build())
                    .hasMessageContaining("between 0 and 4320");
        }

        @Test
        void checksTheRolesItIsAskedToNudgeOn() {
            assertThatThrownBy(
                            () -> NudgePolicy.builder().roles(List.of("ashore")).build())
                    .hasMessageContaining("does not name a day role");
            assertThatThrownBy(() -> NudgePolicy.builder().roles(List.of()).build())
                    .hasMessageContaining("at least one day role");
        }

        @Test
        void dedupesAndOrdersTheRoles() {
            assertThat(NudgePolicy.builder()
                            .roles(List.of("offshift", "onshift", "onshift"))
                            .build()
                            .roles())
                    .containsExactly(DayRole.ONSHIFT, DayRole.OFFSHIFT);
        }
    }

    @Nested
    class QuietHours {

        private final NudgePolicy policy =
                NudgePolicy.builder().quietHours("22:00-06:00").build();

        @Test
        void coversTheOvernightWindow() {
            assertThat(policy.isQuiet(t("23:30"))).isTrue();
            assertThat(policy.isQuiet(t("02:00"))).isTrue();
            assertThat(policy.isQuiet(t("06:00"))).isFalse();
            assertThat(policy.isQuiet(t("21:59"))).isFalse();
        }

        @Test
        void worksForADaytimeQuietWindowToo() {
            NudgePolicy daytime =
                    NudgePolicy.builder().quietHours("13:00-15:00").build();
            assertThat(daytime.isQuiet(t("14:00"))).isTrue();
            assertThat(daytime.isQuiet(t("16:00"))).isFalse();
        }

        @Test
        void pushesANudgeToTheEndOfTheQuietWindow() {
            assertThat(policy.firstAllowedTime(t("23:30")).toString()).isEqualTo("06:00");
            assertThat(policy.firstAllowedTime(t("02:00")).toString()).isEqualTo("06:00");
        }

        @Test
        void leavesAnAllowedTimeAlone() {
            assertThat(policy.firstAllowedTime(t("09:00")).toString()).isEqualTo("09:00");
        }

        @Test
        void measuresHowLongTheWaitIs() {
            assertThat(policy.quietMinutesAhead(t("23:30"))).isEqualTo(390);
            assertThat(policy.quietMinutesAhead(t("05:30"))).isEqualTo(30);
            assertThat(policy.quietMinutesAhead(t("09:00"))).isZero();
        }
    }

    @Nested
    class Silence {

        @Test
        void isSilentWithNoChannel() {
            assertThat(DEFAULT.withChannel(NudgeChannel.NONE).isSilent()).isTrue();
        }

        @Test
        void isSilentWithADailyCapOfZero() {
            assertThat(NudgePolicy.builder().maxPerDay(0).build().isSilent()).isTrue();
        }

        @Test
        void isNotSilentOtherwise() {
            assertThat(DEFAULT.isSilent()).isFalse();
        }
    }

    @Nested
    class ShiftingATime {

        @Test
        void movesForwardAndWraps() {
            assertThat(NudgePolicy.shiftedBy(t("22:00"), 480).toString()).isEqualTo("06:00");
        }
    }
}
