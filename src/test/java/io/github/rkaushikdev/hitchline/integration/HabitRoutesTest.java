package io.github.rkaushikdev.hitchline.integration;

import static io.github.rkaushikdev.hitchline.support.Inject.body;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.HttpServer;
import io.github.rkaushikdev.hitchline.http.routes.CheckInRoutes;
import io.github.rkaushikdev.hitchline.http.routes.HabitRoutes;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HabitRoutesTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");

    private static final ApiToken ADA_TOKEN = new ApiToken("ada-token-0123456789", ADA, List.of("read", "write"));
    private static final ApiToken BO_TOKEN = new ApiToken("bo-token-01234567890", BO, List.of("read", "write"));
    private static final ApiToken READER_TOKEN = new ApiToken("reader-token-012345", ADA, List.of("read"));

    private Inject app;

    @BeforeEach
    void build() {
        MemoryStore store = MemoryStore.create(Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds());
        HttpApp built = HttpServer.build(
                Config.read(Map.of()).withStore(StoreKind.MEMORY).withLogLevel(LogLevel.SILENT), store);
        Auth.register(built, List.of(ADA_TOKEN, BO_TOKEN, READER_TOKEN));
        HabitRoutes.register(built, store);
        CheckInRoutes.register(built, store);
        app = Inject.into(built);
        store.members().save(Fixtures.aMember());
        store.members().save(Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist")));
    }

    private Reply call(String method, String url, String token) {
        return app.call(method, url, token);
    }

    private Reply call(String method, String url, String token, ObjectNode payload) {
        return app.call(method, url, token, payload);
    }

    private ObjectNode aHabitBody() {
        return body(
                "memberId",
                ADA,
                "name",
                "Twenty minutes on the treadmill",
                "cadence",
                "1/day",
                "startsOn",
                "2025-01-06");
    }

    private Reply createHabit() {
        return createHabit(ADA_TOKEN.token(), aHabitBody());
    }

    private Reply createHabit(String token, ObjectNode payload) {
        return call("POST", "/habits", token, payload);
    }

    private String createdId() {
        return createHabit().json().get("id").asText();
    }

    @Nested
    class CreatingAHabit {

        @Test
        void answers201WithTheStoredHabit() {
            Reply response = createHabit();
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.json().get("id").asText()).startsWith("hab_");
            assertThat(response.json().get("cadence").asText()).isEqualTo("1/day");
        }

        @Test
        void refusesABodyItCannotRead() {
            Reply response = call("POST", "/habits", ADA_TOKEN.token(), body("memberId", ADA));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.json().get("code").asText()).isEqualTo("validation_failed");
        }

        @Test
        void refusesACadenceTheDomainDoesNotKnow() {
            Reply response = createHabit(ADA_TOKEN.token(), aHabitBody().put("cadence", "every day"));
            assertThat(response.status()).isEqualTo(400);
            assertThat(response.json().get("message").asText()).contains("3/hitch");
        }

        @Test
        void stopsSomebodyCreatingAHabitForAnotherMember() {
            assertThat(createHabit(BO_TOKEN.token(), aHabitBody()).status()).isEqualTo(403);
        }

        @Test
        void turnsAwayARequestWithNoToken() {
            assertThat(app.send("POST", "/habits", Map.of(), body()).status()).isEqualTo(403);
        }
    }

    @Nested
    class ReadingHabits {

        @Test
        void listsAMemberHabits() {
            createHabit();
            Reply response = call("GET", "/habits?memberId=" + ADA, ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().get("items")).hasSize(1);
        }

        @Test
        void needsAMemberId() {
            assertThat(call("GET", "/habits", ADA_TOKEN.token()).status()).isEqualTo(400);
        }

        @Test
        void readsOneById() {
            String id = createdId();
            Reply response = call("GET", "/habits/" + id, ADA_TOKEN.token());
            assertThat(response.json().get("name").asText()).isEqualTo("Twenty minutes on the treadmill");
        }

        @Test
        void answers404ForAnIdItDoesNotHold() {
            assertThat(call("GET", "/habits/hab_9999999999999999", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(404);
        }

        @Test
        void stopsSomebodyReadingAnotherMemberHabit() {
            String id = createdId();
            assertThat(call("GET", "/habits/" + id, BO_TOKEN.token()).status()).isEqualTo(403);
        }
    }

    @Nested
    class ChangingAHabit {

        @Test
        void renamesIt() {
            String id = createdId();
            Reply response = call("PATCH", "/habits/" + id, ADA_TOKEN.token(), body("name", "Row hard"));
            assertThat(response.json().get("name").asText()).isEqualTo("Row hard");
        }

        @Test
        void clearsATargetWithNull() {
            String id = createHabit(ADA_TOKEN.token(), aHabitBody().put("target", "at least 20 minutes"))
                    .json()
                    .get("id")
                    .asText();
            Reply response = call("PATCH", "/habits/" + id, ADA_TOKEN.token(), body("target", null));
            assertThat(response.json().get("target").isNull()).isTrue();
        }

        @Test
        void needsTheWriteScope() {
            String id = createdId();
            Reply response = call("PATCH", "/habits/" + id, READER_TOKEN.token(), body("name", "Row hard"));
            assertThat(response.status()).isEqualTo(403);
        }

        @Test
        void refusesAnEmptyBody() {
            String id = createdId();
            assertThat(call("PATCH", "/habits/" + id, ADA_TOKEN.token(), body()).status())
                    .isEqualTo(400);
        }
    }

    @Nested
    class RecordingACheckIn {

        private String habitId;

        @BeforeEach
        void createTheHabit() {
            habitId = createdId();
        }

        @Test
        void answers201WithTheStoredRow() {
            Reply response = call("POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId));
            assertThat(response.status()).isEqualTo(201);
            assertThat(response.json().get("habitId").asText()).isEqualTo(habitId);
        }

        @Test
        void answers200WhenAnIdempotencyKeyRepeats() {
            ObjectNode payload = body("habitId", habitId, "idempotencyKey", "watch-2025-01-10");
            Reply first = call("POST", "/check-ins", ADA_TOKEN.token(), payload);
            Reply second = call("POST", "/check-ins", ADA_TOKEN.token(), payload);
            assertThat(first.status()).isEqualTo(201);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.json().get("id")).isEqualTo(first.json().get("id"));
        }

        @Test
        void refusesATimeInTheFuture() {
            Reply response = call(
                    "POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId, "at", "2025-01-20T09:00:00Z"));
            assertThat(response.status()).isEqualTo(400);
        }

        @Test
        void stopsSomebodyCheckingInForAnotherMember() {
            assertThat(call("POST", "/check-ins", BO_TOKEN.token(), body("habitId", habitId))
                            .status())
                    .isEqualTo(403);
        }

        @Test
        void listsWhatAMemberHasRecorded() {
            call("POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId));
            Reply response = call("GET", "/members/" + ADA + "/check-ins", ADA_TOKEN.token());
            assertThat(response.json().get("items")).hasSize(1);
            assertThat(response.json().get("items").get(0).get("onDate").asText())
                    .isEqualTo("2025-01-10");
        }

        @Test
        void narrowsAListToAWindow() {
            call("POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId));
            Reply listed =
                    call("GET", "/members/" + ADA + "/check-ins?window=2025-01-01..2025-01-05", ADA_TOKEN.token());
            assertThat(listed.json().get("items")).isEmpty();
        }

        @Test
        void voidsOne() {
            String id = call("POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId))
                    .json()
                    .get("id")
                    .asText();
            assertThat(call("POST", "/check-ins/" + id + "/void", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(200);
            Reply listed = call("GET", "/members/" + ADA + "/check-ins", ADA_TOKEN.token());
            assertThat(listed.json().get("items")).isEmpty();
        }

        @Test
        void amendsAReading() {
            String id = call("POST", "/check-ins", ADA_TOKEN.token(), body("habitId", habitId, "reading", 20))
                    .json()
                    .get("id")
                    .asText();
            Reply response = call("PATCH", "/check-ins/" + id, ADA_TOKEN.token(), body("reading", 25));
            assertThat(response.json().get("reading").asDouble()).isEqualTo(25);
        }

        @Test
        void answers404ForACheckInItDoesNotHold() {
            assertThat(call("POST", "/check-ins/chk_9999999999999999/void", ADA_TOKEN.token())
                            .status())
                    .isEqualTo(404);
        }
    }
}
