package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.SearchService.HabitQuery;
import io.github.rkaushikdev.hitchline.app.SearchService.TagCount;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");

    private MemoryStore store;
    private SearchService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-06-04T09:00:00Z"), IdSources.sequentialIds());
        service = new SearchService(store);
        store.members().save(Fixtures.aMember());
        store.members().save(Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist")));
        store.habits().save(Fixtures.aHabit(o -> o.id("hab_0000000000000001")
                .name("Twenty minutes on the treadmill")
                .tags("fitness", "cardio")));
        store.habits().save(Fixtures.aHabit(o -> o.id("hab_0000000000000002")
                .name("Bank the sleep hours")
                .tags("rest")));
        store.habits().save(Fixtures.aHabit(o -> o.id("hab_0000000000000003")
                .memberId("mem_FEDCBA9876543210")
                .name("Read the toolbox talk")
                .tags("safety")));
    }

    private List<Habit> habits(HabitQuery query) {
        return service.habits(query);
    }

    private static HabitQuery query() {
        return HabitQuery.everything();
    }

    @Nested
    class SearchingHabits {

        @Test
        void findsEverythingWithNoQueryAtAll() {
            assertThat(habits(query())).hasSize(3);
        }

        @Test
        void narrowsToOneMember() {
            assertThat(habits(query().forMember(ADA))).hasSize(2);
            assertThat(habits(query().forMember(BO))).hasSize(1);
        }

        @Test
        void matchesPartOfANameWhateverTheCase() {
            assertThat(habits(query().containing("TREADMILL")).stream().map(Habit::name))
                    .containsExactly("Twenty minutes on the treadmill");
        }

        @Test
        void matchesATag() {
            assertThat(habits(query().tagged(List.of("rest")))).hasSize(1);
            assertThat(habits(query().tagged(List.of("Fitness")))).hasSize(1);
        }

        @Test
        void matchesAnyTagByDefaultAndEveryTagWhenAsked() {
            assertThat(habits(query().tagged(List.of("fitness", "rest")))).hasSize(2);
            assertThat(habits(query().tagged(List.of("fitness", "rest")).matchingAllTags()))
                    .isEmpty();
            assertThat(habits(query().tagged(List.of("fitness", "cardio")).matchingAllTags()))
                    .hasSize(1);
        }

        @Test
        void narrowsByStatus() {
            Habit paused = Fixtures.aHabit(o -> o.id("hab_0000000000000001").tags("fitness"))
                    .moveTo(HabitStatus.PAUSED);
            store.habits().save(paused);
            assertThat(habits(query().withStatus(HabitStatus.PAUSED))).hasSize(1);
            assertThat(habits(query().withStatus(HabitStatus.ACTIVE))).hasSize(2);
        }

        @Test
        void combinesTheFilters() {
            assertThat(habits(query().forMember(ADA).tagged(List.of("fitness")).containing("twenty")))
                    .hasSize(1);
        }

        @Test
        void sortsByName() {
            assertThat(habits(query().forMember(ADA)).stream().map(Habit::name))
                    .containsExactly("Bank the sleep hours", "Twenty minutes on the treadmill");
        }

        @Test
        void findsNothingForTextThatMatchesNothing() {
            assertThat(habits(query().containing("kayaking"))).isEmpty();
        }

        @Test
        void refusesAnAbsurdlyLongSearch() {
            assertThatThrownBy(() -> habits(query().containing("x".repeat(81)))).hasMessageContaining("at most 80");
        }
    }

    @Nested
    class CountingTags {

        @Test
        void countsEveryTagInUse() {
            assertThat(service.tags(Optional.empty()))
                    .containsExactly(
                            new TagCount("cardio", 1),
                            new TagCount("fitness", 1),
                            new TagCount("rest", 1),
                            new TagCount("safety", 1));
        }

        @Test
        void narrowsToOneMember() {
            assertThat(service.tags(Optional.of(BO)).stream().map(TagCount::tag))
                    .containsExactly("safety");
        }

        @Test
        void putsTheBusiestTagFirst() {
            store.habits().save(Fixtures.aHabit(o -> o.id("hab_0000000000000004")
                    .name("Row the ergometer")
                    .tags("fitness")));
            assertThat(service.tags(Optional.empty()).get(0)).isEqualTo(new TagCount("fitness", 2));
        }
    }
}
