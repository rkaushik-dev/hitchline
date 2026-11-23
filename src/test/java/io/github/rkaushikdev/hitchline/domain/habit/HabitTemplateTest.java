package io.github.rkaushikdev.hitchline.domain.habit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HabitTemplateTest {

    private static HabitTemplate standard(String key) {
        return HabitTemplate.STANDARD_TEMPLATES.stream()
                .filter(template -> template.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class BuildingATemplate {

        @Test
        void parsesTheCadenceAndTheTarget() {
            HabitTemplate template = HabitTemplate.builder("gym", "Gym", "3/hitch")
                    .target("at least 20 minutes")
                    .build();
            assertThat(template.cadence().format()).isEqualTo("3/hitch");
            assertThat(template.target().map(HabitTarget::amount)).hasValue(20.0);
        }

        @Test
        void tidiesTheTags() {
            HabitTemplate template = HabitTemplate.builder("gym", "Gym", "3/hitch")
                    .tags("Fitness", "fitness", " health ")
                    .build();
            assertThat(template.tags()).containsExactly("fitness", "health");
        }

        @Test
        void refusesAKeyThatIsNotAShortLowerCaseName() {
            assertThatThrownBy(() ->
                            HabitTemplate.builder("Gym Session", "x", "1/day").build())
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> HabitTemplate.builder("ab", "x", "1/day").build())
                    .hasMessageContaining("short lower");
        }

        @Test
        void boundsTheGraceWindow() {
            assertThatThrownBy(() -> HabitTemplate.builder("gym", "Gym", "1/day")
                            .graceDays(9)
                            .build())
                    .hasMessageContaining("between 0 and 7");
        }

        @Test
        void passesABadCadenceStraightBack() {
            assertThatThrownBy(() ->
                            HabitTemplate.builder("gym", "Gym", "sometimes").build())
                    .hasMessageContaining("must look like");
        }
    }

    @Nested
    class TheStandardSet {

        @Test
        void coversSafetyRestFitnessAndWellbeing() {
            assertThat(HabitTemplate.STANDARD_TEMPLATES.stream().map(HabitTemplate::key))
                    .containsExactly("toolbox-talk", "rest-hours", "gym-session", "call-home", "kit-check");
        }

        @Test
        void usesABlockCadenceWhereOnceADayWouldBeWrong() {
            assertThat(standard("kit-check").cadence().format()).isEqualTo("1/onshift-block");
        }

        @Test
        void givesTheRestHoursADayOfGrace() {
            assertThat(standard("rest-hours").graceDays()).isEqualTo(1);
        }

        @Test
        void asksForTenHoursOfRestADay() {
            HabitTarget rest = standard("rest-hours").target().orElseThrow();
            assertThat(rest.unit()).isEqualTo(TargetUnit.HOURS);
            assertThat(rest.amount()).isEqualTo(10.0);
            assertThat(rest.comparison()).isEqualTo(TargetComparison.AT_LEAST);
        }
    }

    @Nested
    class ALibrary {

        @Test
        void holdsTheStandardSetOnItsOwn() {
            assertThat(HabitTemplate.library()).hasSize(HabitTemplate.STANDARD_TEMPLATES.size());
        }

        @Test
        void takesExtraTemplates() {
            HabitTemplate extra =
                    HabitTemplate.builder("lifeboat-drill", "Drill", "1/hitch").build();
            assertThat(HabitTemplate.library(List.of(extra))).hasSize(HabitTemplate.STANDARD_TEMPLATES.size() + 1);
        }

        @Test
        void refusesAnExtraTemplateThatReusesAKey() {
            HabitTemplate clash =
                    HabitTemplate.builder("gym-session", "Gym", "1/day").build();
            assertThatThrownBy(() -> HabitTemplate.library(List.of(clash))).isInstanceOf(ConflictException.class);
        }

        @Test
        void findsOneByKey() {
            assertThat(HabitTemplate.find(HabitTemplate.library(), "call-home").name())
                    .isEqualTo("Call home");
        }

        @Test
        void complainsAboutAKeyItDoesNotHold() {
            assertThatThrownBy(() -> HabitTemplate.find(HabitTemplate.library(), "nothing"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void filtersByTag() {
            assertThat(HabitTemplate.tagged(HabitTemplate.library(), "safety").stream()
                            .map(HabitTemplate::key))
                    .containsExactly("toolbox-talk", "kit-check");
            assertThat(HabitTemplate.tagged(HabitTemplate.library(), "Safety")).hasSize(2);
            assertThat(HabitTemplate.tagged(HabitTemplate.library(), "nothing")).isEmpty();
        }
    }

    @Nested
    class DescribingOne {

        @Test
        void readsBackInOneLine() {
            assertThat(HabitTemplate.find(HabitTemplate.library(), "gym-session")
                            .describe())
                    .isEqualTo("gym-session: Twenty minutes in the gym 3/hitch, at least 20 minutes");
        }

        @Test
        void leavesTheTargetOutWhenThereIsNone() {
            assertThat(HabitTemplate.find(HabitTemplate.library(), "call-home").describe())
                    .isEqualTo("call-home: Call home 1/offshift-block");
        }
    }
}
