package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.ErrorCode;
import io.github.rkaushikdev.hitchline.domain.ErrorDetail;
import io.github.rkaushikdev.hitchline.domain.ForbiddenException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.UnsupportedException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.Problems.ProblemBody;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class HttpServerTest {

    static final Config CONFIG =
            Config.read(Map.of()).withStore(StoreKind.MEMORY).withLogLevel(LogLevel.SILENT);

    /** Routes that fail on purpose. */
    @RestController
    static class FailingRoutes {

        @GetMapping("/boom")
        public Object boom() {
            throw new ConflictException("two crews claim the same berth");
        }

        @GetMapping("/kaboom")
        public Object kaboom() {
            throw new IllegalStateException("unexpected");
        }
    }

    private HttpApp app;

    @BeforeEach
    void build() {
        app = HttpServer.build(
                CONFIG, MemoryStore.create(Clocks.fixedClock("2025-02-10T09:00:00Z"), IdSources.randomIds()));
    }

    @Nested
    class MappingErrorsToStatuses {

        @Test
        void mapsEachDomainCode() {
            assertThat(Problems.statusFor(new ValidationException("bad"))).isEqualTo(400);
            assertThat(Problems.statusFor(new NotFoundException("habit", "hab_1")))
                    .isEqualTo(404);
            assertThat(Problems.statusFor(new ConflictException("clash"))).isEqualTo(409);
            assertThat(Problems.statusFor(new ForbiddenException("no"))).isEqualTo(403);
            assertThat(Problems.statusFor(new UnsupportedException("not yet"))).isEqualTo(422);
        }

        @Test
        void mapsAnythingElseToFiveHundred() {
            assertThat(Problems.statusFor(new IllegalStateException("boom"))).isEqualTo(500);
            assertThat(Problems.statusFor("boom")).isEqualTo(500);
        }

        @Test
        void answersWithTheDomainMessageAndDetails() {
            ProblemBody problem = Problems.problemFor(ValidationException.field("cadence", "is not recognised"));
            assertThat(problem.code()).isEqualTo("validation_failed");
            assertThat(problem.details()).containsExactly(new ErrorDetail("cadence", "is not recognised"));
        }

        @Test
        void saysNothingRevealingAboutAnUnexpectedFailure() {
            ProblemBody problem =
                    Problems.problemFor(new IllegalStateException("connection string is postgres://secret"));
            assertThat(problem.code()).isEqualTo("internal_error");
            assertThat(problem.message()).doesNotContain("secret");
        }

        @Test
        void knowsWhichFailuresAreTheCallerFault() {
            assertThat(Problems.isCallerFault(new NotFoundException("habit", "hab_1")))
                    .isTrue();
            assertThat(Problems.isCallerFault(new UnsupportedException("not yet")))
                    .isFalse();
            assertThat(Problems.isCallerFault(new IllegalStateException("boom")))
                    .isFalse();
        }

        @Test
        void wrapsAPlainErrorAsADomainOne() {
            DomainException wrapped = Problems.asDomainError(new IllegalStateException("boom"));
            assertThat(wrapped).isInstanceOf(DomainException.class);
            assertThat(wrapped.getMessage()).isEqualTo("boom");
            assertThat(Problems.asDomainError(new ConflictException("clash")).code())
                    .isEqualTo(ErrorCode.CONFLICT);
        }
    }

    @Nested
    class TheHealthRoute {

        @Test
        void answersOkWithTheClockReading() {
            Reply response = Inject.into(app).get("/health");
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json())
                    .isEqualTo(
                            Json.parse("{\"status\":\"ok\",\"at\":\"2025-02-10T09:00:00.000Z\",\"store\":\"memory\"}"));
        }
    }

    @Nested
    class UnknownRoutes {

        @Test
        void answerWithAProblemBodyRatherThanHtml() {
            Reply response = Inject.into(app).get("/nothing-here");
            assertThat(response.status()).isEqualTo(404);
            JsonNode body = response.json();
            assertThat(body.get("code").asText()).isEqualTo("not_found");
            assertThat(body.get("message").asText()).contains("/nothing-here");
        }
    }

    @Nested
    class TheErrorHandler {

        @Test
        void turnsAThrownDomainErrorIntoItsStatus() {
            app.addRoutes(new FailingRoutes());
            Reply response = Inject.into(app).get("/boom");
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.json().get("code").asText()).isEqualTo("conflict");
            assertThat(response.json().get("message").asText()).isEqualTo("two crews claim the same berth");
        }

        @Test
        void turnsAnythingElseIntoAFiveHundred() {
            app.addRoutes(new FailingRoutes());
            Reply response = Inject.into(app).get("/kaboom");
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.json().get("code").asText()).isEqualTo("internal_error");
            assertThat(response.json().get("details")).isEqualTo(Json.array());
            assertThat(response.body()).doesNotContain("unexpected");
        }
    }
}
