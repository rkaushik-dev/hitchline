package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CliReportTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);

    private MemoryStore store;
    private Recorder context;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
        context = Context.recordingContext(store, CONFIG);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-06")));
        CheckInsService checkIns = new CheckInsService(store);
        checkIns.record(RecordCheckInCommand.of(TREADMILL)
                .withAt("2025-01-06T09:00:00Z")
                .withSource(CheckInSource.SUPERVISOR));
        checkIns.record(RecordCheckInCommand.of(TREADMILL)
                .withAt("2025-01-07T09:00:00Z")
                .withSource(CheckInSource.SUPERVISOR));
        context.clear();
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    private List<String> lines() {
        return Texts.split(context.text(), "\n");
    }

    @Nested
    class TheAdherenceReport {

        @Test
        void printsTheCountsForAWindow() {
            int code = run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).contains("owed      14");
            assertThat(text).contains("met       2");
            assertThat(text).contains("missed    12");
        }

        @Test
        void drawsABarForTheRate() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-07");
            assertThat(context.text()).contains("########## 100%");
        }

        @Test
        void breaksItDownByHitchWhenAsked() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-02-02", "--by-hitch");
            String text = context.text();
            assertThat(text).contains("window");
            assertThat(text).contains("2025-01-06..2025-02-02");
        }

        @Test
        void checksARestLogAgainstTheRestHourRules() {
            store.habits().save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                    .cadence("1/day")
                    .target("at least 10 hours")
                    .startsOn("2025-01-06")));
            new CheckInsService(store)
                    .record(RecordCheckInCommand.of(Ids.habitId("hab_FEDCBA9876543210"))
                            .withAt("2025-01-08T05:00:00Z")
                            .withReading(9));
            context.clear();
            int code = run(
                    "report",
                    "adherence",
                    ADA,
                    "--window",
                    "2025-01-06..2025-01-12",
                    "--rest-hours",
                    "hab_FEDCBA9876543210");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("short_day 2025-01-08..2025-01-08: 9 hours in 1 period");
        }

        @Test
        void saysWhenARestLogIsInsideTheRules() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-12", "--rest-hours", TREADMILL);
            assertThat(context.errorText()).contains("does not log rest in hours");
            store.habits().save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                    .target("at least 10 hours")
                    .startsOn("2025-01-06")));
            context.clear();
            run(
                    "report",
                    "adherence",
                    ADA,
                    "--window",
                    "2025-01-06..2025-01-12",
                    "--rest-hours",
                    "hab_FEDCBA9876543210");
            assertThat(context.text()).contains("inside the rest hour rules");
        }

        @Test
        void readsTheWindowAgainstAQuota() {
            int code = run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-12", "--quota", "2/7");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).contains("quota: at most 2 misses or partials in 7 days");
            assertThat(text).containsPattern("worst +5 in 7 days");
            assertThat(text).contains("quota broken 2025-01-08 to 2025-01-14 with 5");
        }

        @Test
        void countsOnlyMissesAgainstAQuotaWhenAsked() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-07", "--quota", "0/7", "--misses-only");
            assertThat(context.text()).contains("quota: at most 0 misses in 7 days");
            assertThat(context.text()).containsPattern("headroom +0");
            assertThat(run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-07", "--quota", "often"))
                    .isEqualTo(Context.EXIT_MISUSED);
        }

        @Test
        void refusesAWindowItCannotRead() {
            assertThat(run("report", "adherence", ADA, "--window", "january")).isEqualTo(Context.EXIT_MISUSED);
        }

        @Test
        void reportsAMemberItDoesNotHold() {
            int code = run("report", "adherence", "mem_9999999999999999", "--window", "2025-01-06..2025-01-19");
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
        }
    }

    @Nested
    class TheOpenReport {

        @Test
        void listsWhatIsStillOpen() {
            run("report", "open", ADA, "--window", "2025-01-06..2025-02-10");
            List<String> lines = lines();
            assertThat(lines.get(0)).contains("due");
            assertThat(lines.size()).isGreaterThan(2);
        }

        @Test
        void printsOnlyAHeaderWhenNothingIsOpen() {
            run("report", "open", ADA, "--window", "2025-01-06..2025-01-07");
            assertThat(lines()).hasSize(2);
        }
    }

    @Nested
    class TheStreakReport {

        @Test
        void printsTheRun() {
            run("report", "streak", ADA, TREADMILL, "--window", "2025-01-06..2025-01-07");
            assertThat(context.text()).contains("current    2");
            assertThat(context.text()).contains("longest    2");
        }

        @Test
        void carriesAMissOnTheFreezesItIsGiven() {
            int code =
                    run("report", "streak", ADA, TREADMILL, "--window", "2025-01-06..2025-01-08", "--freezes", "1/28");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).containsPattern("current +2");
            assertThat(text).containsPattern("carried +1");
            assertThat(text).containsPattern("freezes left +0 of 1/28");
        }

        @Test
        void refusesAFreezeBudgetItCannotRead() {
            assertThat(run(
                            "report",
                            "streak",
                            ADA,
                            TREADMILL,
                            "--window",
                            "2025-01-06..2025-01-08",
                            "--freezes",
                            "lots"))
                    .isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("must look like 1/28");
        }

        @Test
        void showsAShieldedGap() {
            new RosterService(store)
                    .shieldDays(new ShieldCommand(
                            ADA,
                            "2025-01-08..2025-01-09",
                            ShieldReason.ANNUAL_LEAVE,
                            Optional.empty(),
                            Optional.empty()));
            context.clear();
            run("report", "streak", ADA, TREADMILL, "--window", "2025-01-06..2025-01-09");
            assertThat(context.text()).contains("shielded   2");
        }

        @Test
        void reportsAHabitTheMemberDoesNotHave() {
            int code = run("report", "streak", ADA, "hab_9999999999999999", "--window", "2025-01-06..2025-01-07");
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
        }
    }

    @Nested
    class TheNudgePlan {

        @Test
        void printsWhatThePlannerWouldSend() {
            run("report", "nudges", ADA, "--window", "2025-01-20..2025-02-05");
            assertThat(lines().get(0)).contains("day");
        }
    }

    @Nested
    class TheShapeOfAWindow {

        @Test
        void printsTheRoleSplitWhenAsked() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19", "--shape");
            String text = context.text();
            assertThat(text).contains("role");
            assertThat(text).contains("onshift");
        }

        @Test
        void splitsWhatClosedShortIntoPartialsAndMisses() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19", "--shape");
            assertThat(context.text()).containsPattern("role +owed +met +partial +missed +rate");
            assertThat(context.text()).containsPattern("onshift +14 +2 +0 +12 +14%");
        }

        @Test
        void saysWhatTheTrendIs() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19", "--shape");
            assertThat(context.text()).contains("not enough hitches to say");
        }

        @Test
        void namesTheBusiestHour() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19", "--shape");
            assertThat(context.text()).contains("busiest hour  10:00");
        }

        @Test
        void saysSoWhenNothingHasBeenRecorded() {
            MemoryStore empty =
                    MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
            empty.members().save(Fixtures.aMember());
            context = Context.recordingContext(empty, CONFIG);
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19", "--shape");
            assertThat(context.text()).contains("nothing recorded");
        }

        @Test
        void leavesTheShapeOutUnlessItIsAskedFor() {
            run("report", "adherence", ADA, "--window", "2025-01-06..2025-01-19");
            assertThat(context.text()).doesNotContain("busiest hour");
        }
    }
}
