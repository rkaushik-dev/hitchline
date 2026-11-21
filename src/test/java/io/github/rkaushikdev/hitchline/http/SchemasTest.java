package io.github.rkaushikdev.hitchline.http;

import static io.github.rkaushikdev.hitchline.support.Inject.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.http.Schemas.CreateCrewBody;
import io.github.rkaushikdev.hitchline.http.Schemas.CreateHabitBody;
import io.github.rkaushikdev.hitchline.http.Schemas.RecordCheckInBody;
import io.github.rkaushikdev.hitchline.http.Schemas.TargetChange;
import io.github.rkaushikdev.hitchline.http.Schemas.UpdateHabitBody;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SchemasTest {

    private static final String ADA = "mem_0123456789ABCDEF";
    private static final String TREADMILL = "hab_0123456789ABCDEF";

    @Nested
    class IdShapes {

        @Test
        void acceptsAWellFormedId() {
            assertThat(Schemas.parseBody(Schemas.MEMBER_ID, TextNode.valueOf(ADA)))
                    .isEqualTo(ADA);
            assertThat(Schemas.parseBody(Schemas.HABIT_ID, TextNode.valueOf(TREADMILL)))
                    .isEqualTo(TREADMILL);
        }

        @Test
        void refusesTheWrongPrefixOrAShortSuffix() {
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.MEMBER_ID, TextNode.valueOf(TREADMILL)))
                    .hasMessageContaining("must be a mem id");
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.MEMBER_ID, TextNode.valueOf("mem_0123")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesTheLettersTheAlphabetLeavesOut() {
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.MEMBER_ID, TextNode.valueOf("mem_ILOU456789ABCDEF")))
                    .hasMessageContaining("must be a mem id");
        }
    }

    @Nested
    class CreatingAHabit {

        private ObjectNode smallest() {
            return body("memberId", ADA, "name", "Twenty minutes on the treadmill", "cadence", "1/onshift-day");
        }

        @Test
        void acceptsTheSmallestUsefulBody() {
            assertThat(Schemas.parseBody(Schemas.CREATE_HABIT, smallest()))
                    .isEqualTo(new CreateHabitBody(
                            ADA,
                            "Twenty minutes on the treadmill",
                            "1/onshift-day",
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            OptionalInt.empty()));
        }

        @Test
        void acceptsTheOptionalFields() {
            ObjectNode full = smallest()
                    .put("target", "at least 20 minutes")
                    .put("startsOn", "2025-01-06")
                    .put("graceDays", 2);
            assertThat(Schemas.parseBody(Schemas.CREATE_HABIT, full).graceDays())
                    .hasValue(2);
        }

        @Test
        void refusesAShortName() {
            assertThatThrownBy(() ->
                            Schemas.parseBody(Schemas.CREATE_HABIT, smallest().put("name", "go")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesADateThatIsNotIso() {
            assertThatThrownBy(() ->
                            Schemas.parseBody(Schemas.CREATE_HABIT, smallest().put("startsOn", "6 January")))
                    .hasMessageContaining("YYYY-MM-DD");
        }

        @Test
        void refusesTooManyTags() {
            ObjectNode tagged = smallest();
            tagged.set("tags", ApiJson.toTree(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i")));
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.CREATE_HABIT, tagged))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void namesTheFieldThatFailed() {
            ValidationException refused = catchThrowableOfType(
                    ValidationException.class,
                    () -> Schemas.parseBody(Schemas.CREATE_HABIT, smallest().put("name", "go")));
            assertThat(refused).isNotNull();
            assertThat(refused.details().get(0).path()).isEqualTo("name");
        }
    }

    @Nested
    class UpdatingAHabit {

        @Test
        void acceptsOneField() {
            assertThat(Schemas.parseBody(Schemas.UPDATE_HABIT, body("status", "paused")))
                    .isEqualTo(new UpdateHabitBody(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(HabitStatus.PAUSED)));
        }

        @Test
        void acceptsANullTargetWhichClearsIt() {
            assertThat(Schemas.parseBody(Schemas.UPDATE_HABIT, body("target", null))
                            .target())
                    .hasValue(new TargetChange(Optional.empty()));
        }

        @Test
        void refusesAnEmptyBody() {
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.UPDATE_HABIT, body()))
                    .hasMessageContaining("must change something");
        }

        @Test
        void refusesAStatusItDoesNotKnow() {
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.UPDATE_HABIT, body("status", "retired")))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class CheckIns {

        @Test
        void acceptsABareHabitId() {
            assertThat(Schemas.parseBody(Schemas.RECORD_CHECK_IN, body("habitId", TREADMILL)))
                    .isEqualTo(new RecordCheckInBody(
                            TREADMILL,
                            Optional.empty(),
                            OptionalDouble.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Test
        void acceptsATimestampWithAnOffset() {
            assertThat(Schemas.parseBody(
                                    Schemas.RECORD_CHECK_IN, body("habitId", TREADMILL, "at", "2025-01-10T09:00:00Z"))
                            .at())
                    .hasValue("2025-01-10T09:00:00Z");
        }

        @Test
        void refusesATimestampWithoutOne() {
            assertThatThrownBy(() -> Schemas.parseBody(
                            Schemas.RECORD_CHECK_IN, body("habitId", TREADMILL, "at", "2025-01-10 09:00")))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesANegativeReading() {
            assertThatThrownBy(
                            () -> Schemas.parseBody(Schemas.RECORD_CHECK_IN, body("habitId", TREADMILL, "reading", -1)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void acceptsANullReadingOnAnAmendment() {
            assertThat(Schemas.parseBody(Schemas.AMEND_CHECK_IN, body("reading", null))
                            .reading())
                    .isEmpty();
        }
    }

    @Nested
    class RosterBodies {

        @Test
        void acceptsAHold() {
            ObjectNode hold =
                    body("memberId", ADA, "range", "2025-01-20..2025-01-22", "role", "onshift", "reason", "held_over");
            assertThat(Schemas.parseBody(Schemas.HOLD, hold).role()).isEqualTo(DayRole.ONSHIFT);
        }

        @Test
        void refusesAWindowThatIsNotAPairOfDates() {
            ObjectNode hold = body("memberId", ADA, "range", "2025-01-20", "role", "onshift", "reason", "held_over");
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.HOLD, hold))
                    .hasMessageContaining("YYYY-MM-DD..YYYY-MM-DD");
        }

        @Test
        void acceptsAShieldWithAGranter() {
            ObjectNode shield = body(
                    "memberId",
                    ADA,
                    "range",
                    "2025-02-03..2025-02-07",
                    "reason",
                    "training",
                    "grantedBy",
                    "mem_FEDCBA9876543210");
            assertThat(Schemas.parseBody(Schemas.SHIELD, shield).grantedBy()).hasValue("mem_FEDCBA9876543210");
        }

        @Test
        void readsAWindowQuery() {
            assertThat(Schemas.parseBody(Schemas.WINDOW_QUERY, body("window", "2025-01-06..2025-01-19"))
                            .window())
                    .isEqualTo("2025-01-06..2025-01-19");
        }
    }

    @Nested
    class PolicyVisibilityAndCrews {

        @Test
        void acceptsAPartialPolicy() {
            assertThat(Schemas.parseBody(Schemas.POLICY, body("maxPerDay", 2)).maxPerDay())
                    .hasValue(2);
        }

        @Test
        void refusesAPolicyValueOutOfRange() {
            assertThatThrownBy(() -> Schemas.parseBody(Schemas.POLICY, body("maxPerDay", 99)))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void acceptsAVisibilityChangeForOneHabit() {
            assertThat(Schemas.parseBody(Schemas.VISIBILITY, body("habitId", TREADMILL, "visibility", "crew"))
                            .visibility())
                    .hasValue(Visibility.CREW);
        }

        @Test
        void acceptsACrew() {
            ObjectNode crew = body("name", "Alpha production", "site", "Grane B", "siteZone", "Europe/Oslo");
            assertThat(Schemas.parseBody(Schemas.CREATE_CREW, crew))
                    .isEqualTo(new CreateCrewBody("Alpha production", "Grane B", "Europe/Oslo"));
        }

        @Test
        void acceptsAnEnrolment() {
            ObjectNode enrolment = body("memberId", ADA, "from", "2025-01-01", "role", "lead");
            assertThat(Schemas.parseBody(Schemas.ENROL, enrolment).role()).hasValue(CrewRole.LEAD);
        }
    }
}
