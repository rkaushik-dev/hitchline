package io.github.rkaushikdev.hitchline.integration;

import static io.github.rkaushikdev.hitchline.support.Inject.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.HttpServer;
import io.github.rkaushikdev.hitchline.http.routes.AdherenceRoutes;
import io.github.rkaushikdev.hitchline.http.routes.RosterRoutes;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RosterRoutesTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private static final ApiToken ADA_TOKEN = new ApiToken("ada-token-0123456789", ADA, List.of("read", "write"));
    private static final ApiToken BO_TOKEN = new ApiToken("bo-token-01234567890", BO, List.of("read"));
    private static final ApiToken ADMIN_TOKEN = new ApiToken("admin-token-0123456", BO, List.of("admin"));

    private MemoryStore store;
    private Inject app;

    @BeforeEach
    void build() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
        HttpApp built = HttpServer.build(
                Config.read(Map.of()).withStore(StoreKind.MEMORY).withLogLevel(LogLevel.SILENT), store);
        Auth.register(built, List.of(ADA_TOKEN, BO_TOKEN, ADMIN_TOKEN));
        RosterRoutes.register(built, store);
        AdherenceRoutes.register(built, store);
        app = Inject.into(built);
        store.members().save(Fixtures.aMember());
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-06")));
    }

    private Reply call(String method, String url, String token) {
        return app.call(method, url, token);
    }

    private Reply call(String method, String url, String token, ObjectNode payload) {
        return app.call(method, url, token, payload);
    }

    @Nested
    class ReadingARoster {

        @Test
        void givesARoleAndAZoneForEachDay() {
            Reply response =
                    call("GET", "/members/" + ADA + "/roster?window=2025-01-19..2025-01-21", ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(200);
            JsonNode days = response.json().get("days");
            assertThat(days).hasSize(3);
            assertThat(days.get(0))
                    .isEqualTo(
                            Json.parse(
                                    "{\"date\":\"2025-01-19\",\"role\":\"onshift\",\"source\":\"pattern\",\"zone\":\"Europe/Oslo\"}"));
        }

        @Test
        void needsAWindow() {
            assertThat(call("GET", "/members/" + ADA + "/roster", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(400);
        }

        @Test
        void refusesAWindowItCannotRead() {
            assertThat(call("GET", "/members/" + ADA + "/roster?window=january", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(400);
        }

        @Test
        void stopsSomebodyReadingAnotherMemberRoster() {
            String url = "/members/" + ADA + "/roster?window=2025-01-19..2025-01-21";
            assertThat(call("GET", url, BO_TOKEN.token()).status()).isEqualTo(403);
        }

        @Test
        void letsAnAdminReadIt() {
            String url = "/members/" + ADA + "/roster?window=2025-01-19..2025-01-21";
            assertThat(call("GET", url, ADMIN_TOKEN.token()).status()).isEqualTo(200);
        }

        @Test
        void findsTheHitchADateSitsIn() {
            Reply response = call("GET", "/members/" + ADA + "/hitch?on=2025-01-30", ADA_TOKEN.token());
            assertThat(response.json().get("hitch").asText()).isEqualTo("2025-01-06..2025-02-02");
        }
    }

    @Nested
    class ChangingARoster {

        @Test
        void recordsAHold() {
            Reply response = call(
                    "POST",
                    "/roster/holds",
                    ADA_TOKEN.token(),
                    body(
                            "memberId",
                            ADA,
                            "range",
                            "2025-01-20..2025-01-22",
                            "role",
                            "onshift",
                            "reason",
                            "held_over",
                            "note",
                            "relief weathered off"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.json().get("range").asText()).isEqualTo("2025-01-20..2025-01-22");
            assertThat(store.overrides().byMember(ADA)).hasSize(1);
        }

        @Test
        void refusesASecondHoldThatClashes() {
            ObjectNode payload =
                    body("memberId", ADA, "range", "2025-01-20..2025-01-22", "role", "onshift", "reason", "held_over");
            call("POST", "/roster/holds", ADA_TOKEN.token(), payload);
            Reply second = call(
                    "POST",
                    "/roster/holds",
                    ADA_TOKEN.token(),
                    payload.deepCopy().put("range", "2025-01-21..2025-01-25"));
            assertThat(second.status()).isEqualTo(409);
        }

        @Test
        void needsTheWriteScope() {
            Reply response = call(
                    "POST",
                    "/roster/holds",
                    BO_TOKEN.token(),
                    body("memberId", BO, "range", "2025-01-20..2025-01-22", "role", "onshift", "reason", "held_over"));
            assertThat(response.status()).isEqualTo(403);
        }

        @Test
        void recordsAShield() {
            Reply response = call(
                    "POST",
                    "/roster/shields",
                    ADA_TOKEN.token(),
                    body("memberId", ADA, "range", "2025-02-03..2025-02-07", "reason", "annual_leave"));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.json().get("grantedBy").isNull()).isTrue();
        }

        @Test
        void refusesAShieldThatNeedsAGranter() {
            Reply response = call(
                    "POST",
                    "/roster/shields",
                    ADA_TOKEN.token(),
                    body("memberId", ADA, "range", "2025-02-03..2025-02-07", "reason", "training"));
            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Nested
    class ReadingAdherence {

        @BeforeEach
        void recordTwoDays() {
            CheckInsService checkIns = new CheckInsService(store);
            checkIns.record(RecordCheckInCommand.of(TREADMILL).withAt("2025-01-06T09:00:00Z"));
            checkIns.record(RecordCheckInCommand.of(TREADMILL).withAt("2025-01-07T09:00:00Z"));
        }

        @Test
        void reportsAWindow() {
            Reply response =
                    call("GET", "/members/" + ADA + "/adherence?window=2025-01-06..2025-01-19", ADA_TOKEN.token());
            JsonNode body = response.json();
            assertThat(body.get("summary").get("owed").asInt()).isEqualTo(14);
            assertThat(body.get("summary").get("met").asInt()).isEqualTo(2);
            assertThat(body.get("results")).hasSize(14);
            assertThat(body.get("byHitch")).hasSize(1);
        }

        @Test
        void reportsAStreak() {
            String url = "/members/" + ADA + "/habits/" + TREADMILL + "/streak?window=2025-01-06..2025-01-07";
            assertThat(call("GET", url, ADA_TOKEN.token()).json().get("current").asInt())
                    .isEqualTo(2);
        }

        @Test
        void answers404ForAHabitTheMemberDoesNotHave() {
            String url = "/members/" + ADA + "/habits/hab_9999999999999999/streak?window=2025-01-06..2025-01-07";
            assertThat(call("GET", url, ADA_TOKEN.token()).status()).isEqualTo(404);
        }

        @Test
        void listsWhatIsStillOpen() {
            Reply response = call("GET", "/members/" + ADA + "/open?window=2025-01-06..2025-01-31", ADA_TOKEN.token());
            JsonNode open = response.json().get("open");
            assertThat(open.isArray()).isTrue();
            open.forEach(entry -> assertThat(entry.get("status").asText()).isEqualTo("pending"));
        }

        @Test
        void needsAWindow() {
            assertThat(call("GET", "/members/" + ADA + "/adherence", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(400);
        }
    }
}
