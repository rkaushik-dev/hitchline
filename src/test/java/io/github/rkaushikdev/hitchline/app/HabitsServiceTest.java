package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.HabitsService.CreateHabitCommand;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.MemberStatus;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HabitsServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");

    private MemoryStore store;
    private HabitsService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds());
        service = new HabitsService(store);
        store.members().save(Fixtures.aMember());
    }

    private Habit created(String name, String cadence) {
        return service.create(CreateHabitCommand.of(ADA, name, cadence));
    }

    @Nested
    class CreatingAHabit {

        @Test
        void mintsAnIdAndStoresIt() {
            Habit habit = created("Twenty minutes on the treadmill", "1/onshift-day");
            assertThat(habit.id()).startsWith("hab_");
            assertThat(store.habits().byId(habit.id())).hasValue(habit);
        }

        @Test
        void startsTodayInTheMemberHomeZoneWhenNoDateIsGiven() {
            assertThat(created("Stretch", "1/day").startsOn().toString()).isEqualTo("2025-01-10");
        }

        @Test
        void takesTheDatesItIsGiven() {
            Habit habit = service.create(CreateHabitCommand.of(ADA, "Stretch", "1/day")
                    .withStartsOn("2025-02-01")
                    .withEndsOn("2025-03-31"));
            assertThat(habit.startsOn().toString()).isEqualTo("2025-02-01");
            assertThat(habit.endsOn().map(Object::toString)).hasValue("2025-03-31");
        }

        @Test
        void parsesTheCadenceAndTarget() {
            Habit habit =
                    service.create(CreateHabitCommand.of(ADA, "Row", "3/hitch").withTarget("at least 20 minutes"));
            assertThat(habit.cadence().format()).isEqualTo("3/hitch");
            assertThat(habit.target().map(HabitTarget::format)).hasValue("at least 20 minutes");
        }

        @Test
        void refusesAMemberItHasNeverHeardOf() {
            assertThatThrownBy(() -> service.create(
                            CreateHabitCommand.of(Ids.crewMemberId("mem_FEDCBA9876543210"), "Stretch", "1/day")))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusesAMemberWhoHasLeft() {
            store.members().save(Fixtures.aMember().moveTo(MemberStatus.DEPARTED));
            assertThatThrownBy(() -> created("Stretch", "1/day")).isInstanceOf(ValidationException.class);
        }

        @Test
        void passesABadCadenceStraightBack() {
            assertThatThrownBy(() -> created("Stretch", "every day")).hasMessageContaining("must look like 3/hitch");
        }

        @Test
        void stampsTheMomentItWasCreated() {
            assertThat(created("Stretch", "1/day").createdAt())
                    .isEqualTo(store.clock().now());
        }
    }

    @Nested
    class ReadingHabits {

        @BeforeEach
        void seed() {
            created("Row the ergometer", "1/day");
            created("Bank the sleep hours", "1/day");
        }

        @Test
        void listsAMemberHabitsByName() {
            assertThat(service.listFor(ADA).stream().map(Habit::name))
                    .containsExactly("Bank the sleep hours", "Row the ergometer");
        }

        @Test
        void getsOneById() {
            Habit first = service.listFor(ADA).get(0);
            assertThat(service.get(first.id()).name()).isEqualTo(first.name());
        }

        @Test
        void complainsAboutAnIdItDoesNotHold() {
            assertThatThrownBy(() -> service.get(Ids.habitId("hab_9999999999999999")))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void givesAnEmptyListForSomebodyWithNone() {
            assertThat(service.listFor(Ids.crewMemberId("mem_FEDCBA9876543210")))
                    .isEmpty();
        }
    }

    @Nested
    class EditingAHabit {

        @Test
        void renamesAndStores() {
            Habit habit = created("Row", "1/day");
            service.rename(habit.id(), "Row the ergometer");
            assertThat(store.habits().byId(habit.id()).map(Habit::name)).hasValue("Row the ergometer");
        }

        @Test
        void pausesAndResumes() {
            Habit habit = created("Row", "1/day");
            assertThat(service.moveTo(habit.id(), HabitStatus.PAUSED).status()).isEqualTo(HabitStatus.PAUSED);
            assertThat(service.moveTo(habit.id(), HabitStatus.ACTIVE).status()).isEqualTo(HabitStatus.ACTIVE);
        }

        @Test
        void changesTheCadence() {
            Habit habit = created("Row", "1/day");
            assertThat(service.changeCadence(habit.id(), "2/onshift-day")
                            .cadence()
                            .format())
                    .isEqualTo("2/onshift-day");
        }

        @Test
        void setsAndClearsTheTarget() {
            Habit habit = created("Row", "1/day");
            assertThat(service.changeTarget(habit.id(), Optional.of("at least 5 km"))
                            .target()
                            .map(HabitTarget::amount))
                    .hasValue(5.0);
            assertThat(service.changeTarget(habit.id(), Optional.empty()).target())
                    .isEmpty();
        }

        @Test
        void replacesTheTags() {
            Habit habit = created("Row", "1/day");
            assertThat(service.changeTags(habit.id(), List.of("Fitness", "cardio"))
                            .tags())
                    .containsExactly("cardio", "fitness");
        }

        @Test
        void refusesToEditAnArchivedHabit() {
            Habit habit = created("Row", "1/day");
            service.moveTo(habit.id(), HabitStatus.ARCHIVED);
            assertThatThrownBy(() -> service.changeCadence(habit.id(), "3/hitch"))
                    .hasMessageContaining("archived habit");
        }
    }
}
