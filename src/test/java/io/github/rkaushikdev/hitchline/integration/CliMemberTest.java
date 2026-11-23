package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ErrorDetail;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CliMemberTest {

    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);
    private static final String[] HOLD_MISSING = {
        "member",
        "hold",
        "mem_9999999999999999",
        "--window",
        "2025-01-20..2025-01-22",
        "--role",
        "onshift",
        "--reason",
        "held_over"
    };

    private MemoryStore store;
    private Recorder context;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-03-10T09:00:00Z"), IdSources.sequentialIds());
        context = Context.recordingContext(store, CONFIG);
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    private List<String> lines() {
        return Texts.split(context.text(), "\n");
    }

    private int addAdaOnTheDefaultZones() {
        return run("member", "add", "--name", "Ada Vasquez", "--pattern", "14/14", "--anchor", "2025-01-06");
    }

    private String firstMember() {
        return store.members().all().stream().findFirst().map(CrewMember::id).orElse("");
    }

    private static String[] concat(String[] head, String... tail) {
        List<String> all = new ArrayList<>(List.of(head));
        all.addAll(List.of(tail));
        return all.toArray(String[]::new);
    }

    @Nested
    class RunningACommandBody {

        @Test
        void answersZeroWhenItWorks() {
            assertThat(Context.runCommand(context, () -> {})).isEqualTo(Context.EXIT_OK);
        }

        @Test
        void answersTwoOnAValidationFailureAndPrintsTheDetail() {
            int code = Context.runCommand(context, () -> {
                throw ValidationException.field("cadence", "is not recognised");
            });
            assertThat(code).isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("cadence is not recognised");
        }

        @Test
        void answersOneOnAnyOtherDomainFailure() {
            int code = Context.runCommand(context, () -> {
                throw new ConflictException("clash");
            });
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("conflict: clash");
        }

        @Test
        void letsAnUnexpectedErrorThroughWithItsStack() {
            IllegalStateException bug = new IllegalStateException("bug");
            assertThatThrownBy(() -> Context.runCommand(context, () -> {
                        throw bug;
                    }))
                    .isSameAs(bug)
                    .hasMessage("bug");
        }

        @Test
        void printsADetailWithNoPathAgainstADash() {
            Context.runCommand(context, () -> {
                throw new ValidationException("habit is invalid", List.of(new ErrorDetail("", "nothing fits")));
            });
            assertThat(context.errorText()).contains("- nothing fits");
        }
    }

    @Nested
    class MemberAdd {

        @Test
        void storesAMemberAndPrintsTheId() {
            int code = run(
                    "member",
                    "add",
                    "--name",
                    "Ada Vasquez",
                    "--pattern",
                    "14/14",
                    "--anchor",
                    "2025-01-06",
                    "--home",
                    "Europe/Lisbon",
                    "--site",
                    "Europe/Oslo");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(store.members().all()).hasSize(1);
            assertThat(context.text()).contains("Ada Vasquez");
        }

        @Test
        void pinsTheRotaOnTheDaySomebodyFliesOut() {
            int code = run(
                    "member",
                    "add",
                    "--name",
                    "Ada Vasquez",
                    "--pattern",
                    "2standby 12on 14off",
                    "--crew-change",
                    "2025-03-03");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            CrewMember ada = store.members().all().get(0);
            assertThat(ada.rotation().roleOn(CivilDate.parse("2025-03-02"))).isEqualTo(DayRole.STANDBY);
            assertThat(ada.rotation().roleOn(CivilDate.parse("2025-03-03"))).isEqualTo(DayRole.ONSHIFT);
            assertThat(ada.rotation().phase()).isEqualTo(2);
        }

        @Test
        void addsABackToBackOnTheRotaAndSiteOfTheMemberRelieved() {
            run(
                    "member",
                    "add",
                    "--name",
                    "Ada Vasquez",
                    "--pattern",
                    "14/14",
                    "--crew-change",
                    "2025-01-06",
                    "--site",
                    "Europe/Oslo");
            String ada = firstMember();
            assertThat(run("member", "add", "--name", "Bo Lindqvist", "--relief-of", ada))
                    .isEqualTo(Context.EXIT_OK);
            CrewMember bo = store.members().all().stream()
                    .filter(member -> !member.id().equals(ada))
                    .findFirst()
                    .orElseThrow();
            assertThat(bo.siteZone()).isEqualTo("Europe/Oslo");
            assertThat(bo.rotation().roleOn(CivilDate.parse("2025-01-20"))).isEqualTo(DayRole.ONSHIFT);
            assertThat(bo.rotation().roleOn(CivilDate.parse("2025-01-19"))).isEqualTo(DayRole.OFFSHIFT);
        }

        @Test
        void refusesAReliefThatAlsoNamesARota() {
            addAdaOnTheDefaultZones();
            String ada = firstMember();
            int code = run("member", "add", "--name", "Bo Lindqvist", "--relief-of", ada, "--pattern", "21/21");
            assertThat(code).isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("takes the rota of the member relieved");
            assertThat(run("member", "add", "--name", "Bo Lindqvist", "--relief-of", "mem_9999999999999999"))
                    .isEqualTo(Context.EXIT_FAILED);
            assertThat(run("member", "add", "--name", "Bo Lindqvist", "--anchor", "2025-01-06"))
                    .isEqualTo(Context.EXIT_MISUSED);
            assertThat(store.members().all()).hasSize(1);
        }

        @Test
        void wantsEitherAnAnchorOrACrewChangeButNotBoth() {
            String[] base = {"member", "add", "--name", "Ada Vasquez", "--pattern", "14/14"};
            assertThat(run(base)).isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("give either --anchor or --crew-change");
            assertThat(run(concat(base, "--anchor", "2025-01-06", "--crew-change", "2025-01-06")))
                    .isEqualTo(Context.EXIT_MISUSED);
            assertThat(store.members().all()).isEmpty();
        }

        @Test
        void refusesAPatternItCannotRead() {
            int code = run(
                    "member", "add", "--name", "Ada Vasquez", "--pattern", "two weeks on", "--anchor", "2025-01-06");
            assertThat(code).isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("cannot read the segment");
        }

        @Test
        void refusesAZoneThePlatformDoesNotKnow() {
            int code = run(
                    "member",
                    "add",
                    "--name",
                    "Ada Vasquez",
                    "--pattern",
                    "14/14",
                    "--anchor",
                    "2025-01-06",
                    "--site",
                    "North Sea/Alpha");
            assertThat(code).isEqualTo(Context.EXIT_MISUSED);
        }
    }

    @Nested
    class MemberList {

        @Test
        void printsATableWithAHeader() {
            addAdaOnTheDefaultZones();
            context.clear();
            run("member", "list");
            List<String> lines = lines();
            assertThat(lines.get(0)).contains("id");
            assertThat(lines.get(2)).contains("Ada Vasquez");
        }

        @Test
        void namesTheRotaWhenTheCrewingOfficeHasANameForIt() {
            addAdaOnTheDefaultZones();
            run("member", "add", "--name", "Bo Lindqvist", "--pattern", "DuPont", "--anchor", "2025-01-06");
            run("member", "add", "--name", "Cy Moreau", "--pattern", "10on 4off", "--anchor", "2025-01-06");
            context.clear();
            run("member", "list");
            String text = context.text();
            assertThat(text).contains("rota");
            assertThat(text).contains("equal-time");
            assertThat(text).contains("dupont");
            assertThat(text).contains("10on 4off");
        }

        @Test
        void printsJustAHeaderWhenTheStoreIsEmpty() {
            run("member", "list");
            assertThat(lines()).hasSize(2);
        }
    }

    @Nested
    class MemberRoster {

        private String addAda() {
            run(
                    "member",
                    "add",
                    "--name",
                    "Ada Vasquez",
                    "--pattern",
                    "14/14",
                    "--anchor",
                    "2025-01-06",
                    "--home",
                    "Europe/Lisbon",
                    "--site",
                    "Europe/Oslo");
            String id = firstMember();
            context.clear();
            return id;
        }

        @Test
        void printsARowForEachDay() {
            String id = addAda();
            run("member", "roster", id, "--window", "2025-01-19..2025-01-21");
            List<String> lines = lines();
            assertThat(lines).hasSize(5);
            assertThat(lines.get(2)).contains("onshift");
            assertThat(lines.get(3)).contains("offshift");
        }

        @Test
        void showsABackToBackBesideTheRoster() {
            String id = addAda();
            run("member", "add", "--name", "Bo Lindqvist", "--relief-of", id);
            String bo = store.members().all().stream()
                    .map(CrewMember::id)
                    .filter(other -> !other.equals(id))
                    .findFirst()
                    .orElseThrow();
            context.clear();
            run("member", "roster", id, "--window", "2025-01-19..2025-01-20", "--beside", bo);
            List<String> lines = lines();
            assertThat(lines.get(0)).contains("beside");
            assertThat(lines.get(2)).containsPattern("2025-01-19 +onshift .*offshift");
            assertThat(lines.get(3)).containsPattern("2025-01-20 +offshift .*onshift");
        }

        @Test
        void countsEachHitchAsWorkedAgainstAsRostered() {
            String id = addAda();
            run(
                    "member",
                    "hold",
                    id,
                    "--window",
                    "2025-01-20..2025-01-22",
                    "--role",
                    "onshift",
                    "--reason",
                    "held_over");
            context.clear();
            run("member", "roster", id, "--window", "2025-01-06..2025-01-07", "--by-hitch");
            String text = context.text();
            assertThat(text).contains("rostered");
            assertThat(text).containsPattern("2025-01-06\\.\\.2025-02-02 +14 +17 +3 +0 +held_over 3");
        }

        @Test
        void saysWhenTheRosterIsInsideTheDutyLimits() {
            String id = addAda();
            run("member", "roster", id, "--window", "2025-01-06..2025-02-02", "--check");
            assertThat(context.text()).contains("within the duty limits");
        }

        @Test
        void listsTheTripsThatRunPastTheDutyLimit() {
            String id = addAda();
            run("member", "hold", id, "--window", "2025-01-20..2025-01-27", "--role", "onshift", "--reason", "weather");
            context.clear();
            int code = run("member", "roster", id, "--window", "2025-01-06..2025-02-02", "--check");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("long_trip 2025-01-06..2025-01-27: 22 days away, limit 21");
        }

        @Test
        void checksAgainstTheLimitsItIsGiven() {
            String id = addAda();
            run("member", "roster", id, "--window", "2025-01-06..2025-01-19", "--check", "--max-trip", "10");
            assertThat(context.text()).contains("14 days away, limit 10");
            assertThat(run("member", "roster", id, "--window", "2025-01-06..2025-01-19", "--check", "--max-trip", "0"))
                    .isEqualTo(Context.EXIT_MISUSED);
        }

        @Test
        void reportsAMemberItDoesNotHold() {
            int code = run("member", "roster", "mem_9999999999999999", "--window", "2025-01-19..2025-01-21");
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("not_found");
        }
    }

    @Nested
    class MemberHold {

        @Test
        void recordsAnOverride() {
            addAdaOnTheDefaultZones();
            String id = firstMember();
            int code = run(
                    "member",
                    "hold",
                    id,
                    "--window",
                    "2025-01-20..2025-01-22",
                    "--role",
                    "onshift",
                    "--reason",
                    "held_over");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(store.overrides().byMember(id)).hasSize(1);
        }

        @Test
        void refusesAHoldThatRunsATripPastTheDutyLimit() {
            addAdaOnTheDefaultZones();
            String id = firstMember();
            String[] args = {"member", "hold", id, "--window", "2025-01-20..2025-01-27", "--role", "onshift"};
            int code = run(concat(args, "--reason", "held_over"));
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("22 days away, limit 21");
            assertThat(store.overrides().byMember(id)).isEmpty();
        }

        @Test
        void storesItPastTheLimitWhenTold() {
            addAdaOnTheDefaultZones();
            String id = firstMember();
            String[] args = {"member", "hold", id, "--window", "2025-01-20..2025-01-27", "--role", "onshift"};
            assertThat(run(concat(args, "--reason", "held_over", "--past-limits")))
                    .isEqualTo(Context.EXIT_OK);
            assertThat(store.overrides().byMember(id)).hasSize(1);
        }

        @Test
        void reportsAClash() {
            addAdaOnTheDefaultZones();
            String id = firstMember();
            String[] args = {"member", "hold", id, "--role", "onshift", "--reason", "held_over"};
            run(concat(args, "--window", "2025-01-20..2025-01-22"));
            int code = run(concat(args, "--window", "2025-01-21..2025-01-25"));
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
        }
    }

    @Nested
    class TheNotFoundPath {

        @Test
        void reportsAMissingMemberFromAHold() {
            int code = run(HOLD_MISSING);
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("crew member");
        }

        @Test
        void leavesTheStoreAlone() {
            run(HOLD_MISSING);
            assertThat(store.members().all()).isEmpty();
        }
    }

    @Nested
    class NotFoundExceptionShape {

        @Test
        void namesTheResourceTheCliPrints() {
            assertThat(new NotFoundException("crew member", "mem_1").getMessage())
                    .contains("crew member");
        }
    }

    @Nested
    class ConfigShow {

        @Test
        void printsTheSettingsInForce() {
            assertThat(run("config", "show")).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).contains("memory");
            assertThat(text).contains("writes a minute");
            assertThat(text).contains("api version");
        }

        @Test
        void saysHowManyTokensAreLoadedWithoutPrintingOne() {
            run("config", "show");
            assertThat(context.text()).contains("0 loaded");
        }

        @Test
        void saysSoWhenNothingWouldBeLetIn() {
            run("config", "tokens");
            assertThat(context.text()).contains("every request will be turned away");
        }

        @Test
        void listsATokenByMemberAndScopeNeverInFull() {
            context = Context.recordingContext(
                    store,
                    CONFIG.withTokens(List.of(
                            new ApiToken("ada-token-0123456789", "mem_0123456789ABCDEF", List.of("read", "write")))));
            run("config", "tokens");
            assertThat(context.text()).contains("read|write");
            assertThat(context.text()).doesNotContain("ada-token-0123456789");
        }
    }

    @Nested
    class ConfigVersion {

        @Test
        void namesTheApiVersionAndWhatItIsRunningOn() {
            assertThat(run("config", "version")).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).contains("api");
            assertThat(text).contains(Runtime.version().toString());
            assertThat(text).contains(System.getProperty("os.name"));
        }

        @Test
        void namesTheStoreItWouldOpen() {
            run("config", "version");
            assertThat(context.text()).contains("memory");
        }
    }
}
