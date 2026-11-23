package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CliHabitTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);

    private MemoryStore store;
    private Recorder context;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds());
        context = Context.recordingContext(store, CONFIG);
        store.members().save(Fixtures.aMember());
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    private List<String> lines() {
        return Texts.split(context.text(), "\n");
    }

    private String addHabit(String... extra) {
        context.clear();
        List<String> args = new ArrayList<>(List.of(
                "habit",
                "add",
                ADA,
                "--name",
                "Twenty minutes on the treadmill",
                "--cadence",
                "1/day",
                "--starts",
                "2025-01-06"));
        args.addAll(List.of(extra));
        run(args.toArray(String[]::new));
        String id = Texts.split(context.text(), " ").get(0);
        context.clear();
        return id;
    }

    private Optional<Habit> habit(String id) {
        return store.habits().byId(id);
    }

    private List<CheckIn> checkInsOf(String habitId) {
        return store.checkIns().byHabit(habitId);
    }

    @Nested
    class HabitAdd {

        @Test
        void storesOneAndPrintsTheId() {
            String id = addHabit();
            assertThat(id).startsWith("hab_");
            assertThat(store.habits().byMember(ADA)).hasSize(1);
        }

        @Test
        void takesATargetAndAGraceWindow() {
            String id = addHabit("--target", "at least 20 minutes", "--grace", "2");
            Optional<Habit> stored = habit(id);
            assertThat(stored.flatMap(Habit::target).map(HabitTarget::amount)).hasValue(20.0);
            assertThat(stored.map(Habit::graceDays)).hasValue(2);
        }

        @Test
        void refusesACadenceItCannotRead() {
            int code = run("habit", "add", ADA, "--name", "Row", "--cadence", "every day");
            assertThat(code).isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("3/hitch");
        }

        @Test
        void refusesAMemberItDoesNotHold() {
            int code = run("habit", "add", "mem_9999999999999999", "--name", "Row", "--cadence", "1/day");
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
        }
    }

    @Nested
    class HabitList {

        @Test
        void printsARowForEachHabit() {
            addHabit();
            run("habit", "list", ADA);
            List<String> lines = lines();
            assertThat(lines).hasSize(3);
            assertThat(lines.get(2)).contains("1/day");
            assertThat(lines.get(2)).contains("-");
        }

        @Test
        void printsTheTargetWhenThereIsOne() {
            addHabit("--target", "at least 20 minutes");
            run("habit", "list", ADA);
            assertThat(context.text()).contains("at least 20 minutes");
        }
    }

    @Nested
    class PausingAndResuming {

        @Test
        void movesAHabitBetweenStates() {
            String id = addHabit();
            assertThat(run("habit", "pause", id)).isEqualTo(Context.EXIT_OK);
            assertThat(habit(id).map(Habit::status)).hasValue(HabitStatus.PAUSED);
            run("habit", "resume", id);
            assertThat(habit(id).map(Habit::status)).hasValue(HabitStatus.ACTIVE);
        }

        @Test
        void reportsAHabitItDoesNotHold() {
            assertThat(run("habit", "pause", "hab_9999999999999999")).isEqualTo(Context.EXIT_FAILED);
        }
    }

    @Nested
    class RecordingACheckIn {

        @Test
        void recordsOneAndSaysSo() {
            String id = addHabit();
            int code = run("checkin", "record", id, "--at", "2025-01-08T07:00:00Z");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("recorded chk_");
        }

        @Test
        void takesAReading() {
            String id = addHabit("--target", "at least 20 minutes");
            run("checkin", "record", id, "--reading", "25");
            List<CheckIn> stored = checkInsOf(id);
            assertThat(stored).isNotEmpty();
            assertThat(stored.get(0).reading()).hasValue(25);
        }

        @Test
        void defaultsTheSourceToSupervisorSoABackfillIsAllowed() {
            String id = addHabit();
            int code = run("checkin", "record", id, "--at", "2025-01-06T07:00:00Z");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            List<CheckIn> stored = checkInsOf(id);
            assertThat(stored).isNotEmpty();
            assertThat(stored.get(0).source()).isEqualTo(CheckInSource.SUPERVISOR);
        }

        @Test
        void refusesATimeInTheFuture() {
            String id = addHabit();
            int code = run("checkin", "record", id, "--at", "2025-02-01T07:00:00Z");
            assertThat(code).isEqualTo(Context.EXIT_MISUSED);
        }
    }

    @Nested
    class ListingAndVoidingCheckIns {

        @Test
        void listsWhatIsRecordedWithItsRotationDay() {
            String id = addHabit();
            run("checkin", "record", id, "--at", "2025-01-08T07:00:00Z");
            context.clear();
            run("checkin", "list", ADA);
            List<String> lines = lines();
            assertThat(lines.get(2)).contains("2025-01-08");
            assertThat(lines.get(2)).contains("onshift");
        }

        @Test
        void marksATapRepeatedInsideTwoMinutesAsNotCounted() {
            String id = addHabit();
            run("checkin", "record", id, "--at", "2025-01-08T07:00:00Z");
            run("checkin", "record", id, "--at", "2025-01-08T07:01:00Z");
            context.clear();
            run("checkin", "list", ADA);
            List<String> lines = lines();
            assertThat(lines.get(0)).contains("counted");
            assertThat(lines.get(2)).endsWith("yes");
            assertThat(lines.get(3)).endsWith("repeat");
        }

        @Test
        void narrowsToAWindow() {
            String id = addHabit();
            run("checkin", "record", id, "--at", "2025-01-08T07:00:00Z");
            context.clear();
            run("checkin", "list", ADA, "--window", "2025-01-01..2025-01-05");
            assertThat(lines()).hasSize(2);
        }

        @Test
        void voidsOneAndDropsItFromTheList() {
            String id = addHabit();
            run("checkin", "record", id, "--at", "2025-01-08T07:00:00Z");
            String recorded =
                    checkInsOf(id).stream().findFirst().map(CheckIn::id).orElse("");
            context.clear();
            assertThat(run("checkin", "void", recorded)).isEqualTo(Context.EXIT_OK);
            context.clear();
            run("checkin", "list", ADA);
            assertThat(lines()).hasSize(2);
        }

        @Test
        void reportsACheckInItDoesNotHold() {
            assertThat(run("checkin", "void", "chk_9999999999999999")).isEqualTo(Context.EXIT_FAILED);
        }
    }
}
