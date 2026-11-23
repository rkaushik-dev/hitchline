package io.github.rkaushikdev.hitchline.integration;

import static io.github.rkaushikdev.hitchline.support.Inject.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.http.Api;
import io.github.rkaushikdev.hitchline.http.Api.ApiOptions;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ApiTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");

    private static final ApiToken ADA_TOKEN = new ApiToken("ada-token-0123456789", ADA, List.of("read", "write"));
    private static final ApiToken BO_TOKEN = new ApiToken("bo-token-01234567890", BO, List.of("read", "write"));
    private static final ApiToken ADMIN_TOKEN = new ApiToken("admin-token-0123456", BO, List.of("admin"));

    private static final Config CONFIG = Config.read(Map.of())
            .withStore(StoreKind.MEMORY)
            .withLogLevel(LogLevel.SILENT)
            .withTokens(List.of(ADA_TOKEN, BO_TOKEN, ADMIN_TOKEN));

    private Inject app;

    @BeforeEach
    void build() {
        MemoryStore store = MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
        app = Inject.into(Api.build(CONFIG, store));
        store.members().save(Fixtures.aMember());
        store.members().save(Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist")));
    }

    private Reply call(String method, String url, String token) {
        return app.call(method, url, token);
    }

    private Reply call(String method, String url, String token, ObjectNode payload) {
        return app.call(method, url, token, payload);
    }

    private static List<String> keysOf(JsonNode tree) {
        List<String> keys = new ArrayList<>();
        tree.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private String habitFor(String member, String token, String name) {
        return call(
                        "POST",
                        "/habits",
                        token,
                        body("memberId", member, "name", name, "cadence", "1/day", "startsOn", "2025-01-06"))
                .json()
                .get("id")
                .asText();
    }

    @Nested
    class TheWholeApi {

        @Test
        void answersTheHealthCheckWithoutAToken() {
            assertThat(app.get("/health").status()).isEqualTo(200);
        }

        @Test
        void servesEveryFamilyOfRoute() {
            Reply created = call(
                    "POST",
                    "/habits",
                    ADA_TOKEN.token(),
                    body(
                            "memberId",
                            ADA,
                            "name",
                            "Twenty minutes on the treadmill",
                            "cadence",
                            "1/onshift-day",
                            "startsOn",
                            "2025-01-06"));
            assertThat(created.status()).isEqualTo(201);
            String habitId = created.json().get("id").asText();

            assertThat(call("POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId))
                            .status())
                    .isEqualTo(201);
            String window = "window=2025-01-06..2025-01-19";
            assertThat(call("GET", "/members/" + ADA + "/roster?" + window, ADA_TOKEN.token())
                            .status())
                    .isEqualTo(200);
            assertThat(call("GET", "/members/" + ADA + "/adherence?" + window, ADA_TOKEN.token())
                            .status())
                    .isEqualTo(200);
            assertThat(call("GET", "/members/" + ADA + "/nudges?" + window, ADA_TOKEN.token())
                            .status())
                    .isEqualTo(200);
        }
    }

    @Nested
    class CrewsOverHttp {

        private String makeCrew() {
            return call(
                            "POST",
                            "/crews",
                            ADMIN_TOKEN.token(),
                            body("name", "Alpha production", "site", "Grane B", "siteZone", "Europe/Oslo"))
                    .json()
                    .get("id")
                    .asText();
        }

        private Reply enrol(String crewId, String member, String from) {
            return call(
                    "POST",
                    "/crews/" + crewId + "/members",
                    ADMIN_TOKEN.token(),
                    body("memberId", member, "from", from));
        }

        @Test
        void createsACrewAndEnrolsSomebody() {
            Reply enrolled = enrol(makeCrew(), ADA, "2025-01-01");
            assertThat(enrolled.status()).isEqualTo(201);
            assertThat(enrolled.json().get("role").asText()).isEqualTo("member");
        }

        @Test
        void needsTheAdminScopeToCreateOne() {
            Reply response = call(
                    "POST",
                    "/crews",
                    ADA_TOKEN.token(),
                    body("name", "Alpha production", "site", "Grane B", "siteZone", "Europe/Oslo"));
            assertThat(response.status()).isEqualTo(403);
        }

        @Test
        void listsWhoWasInACrewOnADate() {
            String crewId = makeCrew();
            enrol(crewId, ADA, "2025-01-01");
            Reply response = call("GET", "/crews/" + crewId + "/members?on=2025-02-01", ADA_TOKEN.token());
            List<String> names = new ArrayList<>();
            response.json()
                    .get("members")
                    .forEach(member -> names.add(member.get("displayName").asText()));
            assertThat(names).containsExactly("Ada Vasquez");
        }

        @Test
        void needsADateToListACrew() {
            String crewId = makeCrew();
            assertThat(call("GET", "/crews/" + crewId + "/members", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(400);
        }

        @Test
        void refusesToEnrolSomebodyAlreadyInAnotherCrew() {
            String first = makeCrew();
            String second = makeCrew();
            enrol(first, ADA, "2025-01-01");
            assertThat(enrol(second, ADA, "2025-02-01").status()).isEqualTo(409);
        }
    }

    @Nested
    class VisibilityOverHttp {

        @Test
        void setsAFallback() {
            Reply response =
                    call("PUT", "/members/" + ADA + "/visibility", ADA_TOKEN.token(), body("fallback", "crew"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().get("fallback").asText()).isEqualTo("crew");
        }

        @Test
        void setsOneHabitApartFromTheFallback() {
            String habitId = call(
                            "POST",
                            "/habits",
                            ADA_TOKEN.token(),
                            body("memberId", ADA, "name", "Bank the sleep hours", "cadence", "1/day"))
                    .json()
                    .get("id")
                    .asText();
            Reply response = call(
                    "PUT",
                    "/members/" + ADA + "/visibility",
                    ADA_TOKEN.token(),
                    body("habitId", habitId, "visibility", "lead_only"));
            assertThat(response.json().get("perHabit"))
                    .isEqualTo(ApiTest.arrayOf(body("habitId", habitId, "visibility", "lead_only")));
        }

        @Test
        void refusesAHabitWithNoVisibilityBesideIt() {
            Reply response = call(
                    "PUT",
                    "/members/" + ADA + "/visibility",
                    ADA_TOKEN.token(),
                    body("habitId", "hab_0123456789ABCDEF"));
            assertThat(response.status()).isEqualTo(400);
        }

        @Test
        void stopsSomebodySettingAnotherMemberVisibility() {
            Reply response = call("PUT", "/members/" + ADA + "/visibility", BO_TOKEN.token(), body("fallback", "crew"));
            assertThat(response.status()).isEqualTo(403);
        }
    }

    private static JsonNode arrayOf(JsonNode... items) {
        return Json.array().addAll(List.of(items));
    }

    @Nested
    class NudgePolicyOverHttp {

        @Test
        void readsTheDefault() {
            Reply response = call("GET", "/members/" + ADA + "/nudge-policy", ADA_TOKEN.token());
            assertThat(response.json().get("quietHours").asText()).isEqualTo("22:00-06:00");
        }

        @Test
        void storesAChangeAndReadsItBack() {
            call(
                    "PUT",
                    "/members/" + ADA + "/nudge-policy",
                    ADA_TOKEN.token(),
                    body("maxPerDay", 1, "quietHours", "23:00-07:00"));
            JsonNode body = call("GET", "/members/" + ADA + "/nudge-policy", ADA_TOKEN.token())
                    .json();
            assertThat(body.get("maxPerDay").asInt()).isEqualTo(1);
            assertThat(body.get("quietHours").asText()).isEqualTo("23:00-07:00");
        }

        @Test
        void refusesAValueOutOfRange() {
            Reply response = call("PUT", "/members/" + ADA + "/nudge-policy", ADA_TOKEN.token(), body("maxPerDay", 99));
            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Nested
    class TheMetricsEndpoint {

        @Test
        void countsWhatItServed() {
            app.get("/health");
            Reply response = app.get("/metrics");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("content-type"))
                    .hasValueSatisfying(type -> assertThat(type).contains("text/plain"));
            assertThat(response.body()).contains("hitchline_requests_total");
            assertThat(response.body()).contains("method=\"GET\"");
        }

        @Test
        void recordsHowLongARequestTook() {
            app.get("/health");
            assertThat(app.get("/metrics").body()).contains("hitchline_request_ms_count");
        }
    }

    @Nested
    class TheRequestId {

        @Test
        void comesBackOnEveryResponse() {
            assertThat(app.get("/health").header("x-request-id")).isPresent();
        }
    }

    @Nested
    class TheWriteLimit {

        @Test
        void turnsABurstAwayWith429() {
            MemoryStore other =
                    MemoryStore.create(Clocks.fixedClock("2025-04-16T09:00:00Z"), IdSources.sequentialIds());
            Inject limited = Inject.into(
                    Api.build(ApiOptions.of(CONFIG, other).withBurst(2).withWritesPerMinute(60)));
            ObjectNode payload = body("name", "x", "site", "y", "siteZone", "UTC");
            limited.call("POST", "/crews", ADA_TOKEN.token(), payload);
            limited.call("POST", "/crews", ADA_TOKEN.token(), payload);
            assertThat(limited.call("POST", "/crews", ADA_TOKEN.token(), payload)
                            .status())
                    .isEqualTo(429);
        }
    }

    @Nested
    class TheCrewComparison {

        private String crewWithBoth() {
            String id = call(
                            "POST",
                            "/crews",
                            ADMIN_TOKEN.token(),
                            body("name", "Alpha production", "site", "Grane B", "siteZone", "Europe/Oslo"))
                    .json()
                    .get("id")
                    .asText();
            call("POST", "/crews/" + id + "/members", ADMIN_TOKEN.token(), body("memberId", ADA, "from", "2025-01-01"));
            call("POST", "/crews/" + id + "/members", ADMIN_TOKEN.token(), body("memberId", BO, "from", "2025-01-01"));
            return id;
        }

        @Test
        void listsALineForEachMember() {
            String id = crewWithBoth();
            String url = "/crews/" + id + "/comparison?window=2025-01-06..2025-01-19&on=2025-01-20";
            Reply response = call("GET", url, ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().get("lines")).hasSize(2);
        }

        @Test
        void needsAWindowAndADate() {
            String id = crewWithBoth();
            assertThat(call("GET", "/crews/" + id + "/comparison?on=2025-01-20", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(400);
            assertThat(call("GET", "/crews/" + id + "/comparison?window=2025-01-06..2025-01-19", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(400);
        }

        @Test
        void saysHowMuchItLeftOut() {
            String id = crewWithBoth();
            habitFor(BO, BO_TOKEN.token(), "Bank the sleep hours");
            String url = "/crews/" + id + "/comparison?window=2025-01-06..2025-01-19&on=2025-01-20";
            JsonNode lines = call("GET", url, ADA_TOKEN.token()).json().get("lines");
            JsonNode bo = null;
            for (JsonNode line : lines) {
                if (line.get("memberId").asText().equals(BO)) {
                    bo = line;
                }
            }
            assertThat(bo).isNotNull();
            assertThat(bo.get("hidden").asInt()).isEqualTo(1);
        }
    }

    @Nested
    class TheApiDescription {

        @Test
        void isServedWithoutAToken() {
            Reply response = app.get("/openapi.json");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().get("openapi").asText()).isEqualTo("3.1.0");
            assertThat(keysOf(response.json().get("paths"))).contains("/habits");
        }
    }

    @Nested
    class HabitTemplates {

        @Test
        void listsTheStandardSet() {
            Reply response = call("GET", "/templates", ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(200);
            List<String> keys = new ArrayList<>();
            response.json()
                    .get("templates")
                    .forEach(template -> keys.add(template.get("key").asText()));
            assertThat(keys).contains("rest-hours");
        }

        @Test
        void givesSomebodyAHabitFromOne() {
            Reply response = call(
                    "POST",
                    "/habits/from-template",
                    ADA_TOKEN.token(),
                    body("memberId", ADA, "key", "gym-session", "startsOn", "2025-01-06"));
            assertThat(response.status()).isEqualTo(201);
            JsonNode body = response.json();
            assertThat(body.get("name").asText()).isEqualTo("Twenty minutes in the gym");
            assertThat(body.get("cadence").asText()).isEqualTo("3/hitch");
            assertThat(body.get("target").asText()).isEqualTo("at least 20 minutes");
        }

        @Test
        void carriesTheGraceWindowATemplateAsksFor() {
            Reply response = call(
                    "POST", "/habits/from-template", ADA_TOKEN.token(), body("memberId", ADA, "key", "rest-hours"));
            assertThat(response.json().get("graceDays").asInt()).isEqualTo(1);
        }

        @Test
        void asksForTheRestHoursInHours() {
            Reply response = call(
                    "POST", "/habits/from-template", ADA_TOKEN.token(), body("memberId", ADA, "key", "rest-hours"));
            assertThat(response.json().get("target").asText()).isEqualTo("at least 10 hours");
            assertThat(response.json().get("cadence").asText()).isEqualTo("1/day");
        }

        @Test
        void answers404ForATemplateItDoesNotHave() {
            Reply response = call(
                    "POST", "/habits/from-template", ADA_TOKEN.token(), body("memberId", ADA, "key", "nothing-here"));
            assertThat(response.status()).isEqualTo(404);
        }

        @Test
        void stopsSomebodyApplyingOneForAnotherMember() {
            Reply response = call(
                    "POST", "/habits/from-template", BO_TOKEN.token(), body("memberId", ADA, "key", "gym-session"));
            assertThat(response.status()).isEqualTo(403);
        }
    }

    @Nested
    class ReadinessOverHttp {

        @Test
        void isServedWithoutATokenAndSaysWhatItChecked() {
            Reply response = app.get("/ready");
            assertThat(response.status()).isEqualTo(200);
            JsonNode body = response.json();
            assertThat(body.get("state").asText()).isEqualTo("pass");
            List<String> names = new ArrayList<>();
            body.get("checks").forEach(check -> names.add(check.get("name").asText()));
            assertThat(names).contains("store");
        }
    }

    @Nested
    class AHandsetSyncingWhatItQueued {

        private ObjectNode batch(ObjectNode... items) {
            ObjectNode payload = body();
            payload.putArray("items").addAll(List.of(items));
            return payload;
        }

        @Test
        void answersPerItem() {
            String habitId = habitFor(ADA, ADA_TOKEN.token(), "Twenty minutes on the treadmill");
            Reply response = call(
                    "POST",
                    "/members/" + ADA + "/sync",
                    ADA_TOKEN.token(),
                    batch(
                            body("localId", "local-1", "habitId", habitId, "at", "2025-01-18T07:00:00Z"),
                            body(
                                    "localId",
                                    "local-2",
                                    "habitId",
                                    habitId,
                                    "at",
                                    "2025-01-19T07:00:00Z",
                                    "reading",
                                    22)));
            assertThat(response.status()).isEqualTo(200);
            JsonNode body = response.json();
            assertThat(body.get("accepted").asInt()).isEqualTo(2);
            body.get("results")
                    .forEach(
                            result -> assertThat(result.get("outcome").asText()).isEqualTo("accepted"));
        }

        @Test
        void doesNotDoubleCountTheSameBatch() {
            String habitId = habitFor(ADA, ADA_TOKEN.token(), "Twenty minutes on the treadmill");
            ObjectNode payload = batch(body("localId", "local-1", "habitId", habitId, "at", "2025-01-18T07:00:00Z"));
            call("POST", "/members/" + ADA + "/sync", ADA_TOKEN.token(), payload);
            Reply second = call("POST", "/members/" + ADA + "/sync", ADA_TOKEN.token(), payload);
            assertThat(second.json().get("duplicates").asInt()).isEqualTo(1);
        }

        @Test
        void refusesABodyItCannotRead() {
            Reply response = call(
                    "POST",
                    "/members/" + ADA + "/sync",
                    ADA_TOKEN.token(),
                    batch(body("localId", "local-1", "habitId", "nope", "at", "yesterday")));
            assertThat(response.status()).isEqualTo(400);
        }

        @Test
        void stopsAHandsetSyncingForSomebodyElse() {
            String habitId = habitFor(ADA, ADA_TOKEN.token(), "Twenty minutes on the treadmill");
            Reply response = call(
                    "POST",
                    "/members/" + ADA + "/sync",
                    BO_TOKEN.token(),
                    batch(body("localId", "local-1", "habitId", habitId, "at", "2025-01-18T07:00:00Z")));
            assertThat(response.status()).isEqualTo(403);
        }
    }

    @Nested
    class TheAuditTrailOverHttp {

        @Test
        void isAdminOnly() {
            assertThat(call("GET", "/audit", ADA_TOKEN.token()).status()).isEqualTo(403);
            assertThat(call("GET", "/audit", ADMIN_TOKEN.token()).status()).isEqualTo(200);
        }

        @Test
        void holdsALineForEverythingTheApiDid() {
            habitFor(ADA, ADA_TOKEN.token(), "Twenty minutes on the treadmill");
            JsonNode body = call("GET", "/audit", ADMIN_TOKEN.token()).json();
            assertThat(body.get("total").asInt()).isEqualTo(1);
            assertThat(body.get("items").get(0).get("name").asText()).isEqualTo("habit.created");
            assertThat(body.get("items").get(0).get("line").asText()).contains("habit.created");
        }

        @Test
        void putsTheNewestFirst() {
            for (String name : List.of("Row the ergometer", "Bank the sleep hours")) {
                habitFor(ADA, ADA_TOKEN.token(), name);
            }
            JsonNode body = call("GET", "/audit", ADMIN_TOKEN.token()).json();
            assertThat(body.get("items").get(0).get("sequence").asInt()).isEqualTo(2);
        }

        @Test
        void narrowsToOneMember() {
            habitFor(ADA, ADA_TOKEN.token(), "Row the ergometer");
            assertThat(call("GET", "/audit?memberId=" + ADA, ADMIN_TOKEN.token())
                            .json()
                            .get("total")
                            .asInt())
                    .isEqualTo(1);
            assertThat(call("GET", "/audit?memberId=" + BO, ADMIN_TOKEN.token())
                            .json()
                            .get("total")
                            .asInt())
                    .isZero();
        }

        @Test
        void pagesLikeEveryOtherList() {
            for (String name : List.of("Row the ergometer", "Bank the sleep hours", "Call home")) {
                habitFor(ADA, ADA_TOKEN.token(), name);
            }
            JsonNode body = call("GET", "/audit?limit=2", ADMIN_TOKEN.token()).json();
            assertThat(body.get("items")).hasSize(2);
            assertThat(body.get("nextCursor").isNull()).isFalse();
        }
    }
}
