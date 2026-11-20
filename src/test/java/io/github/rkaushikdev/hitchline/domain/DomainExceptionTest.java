package io.github.rkaushikdev.hitchline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DomainExceptionTest {

    @Nested
    class TheRootFailure {

        @Test
        void carriesACodeAndDefaultsToNoDetails() {
            DomainException error = new DomainException(ErrorCode.CONFLICT, "two crews claim the same berth");
            assertThat(error.code()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(error.code().wire()).isEqualTo("conflict");
            assertThat(error.details()).isEmpty();
            assertThat(error.name()).isEqualTo("DomainException");
        }

        @Test
        void keepsTheSubclassNameSoLogsStayReadable() {
            assertThat(new ValidationException("bad").name()).isEqualTo("ValidationException");
            assertThat(new ConflictException("bad").name()).isEqualTo("ConflictException");
            assertThat(new ForbiddenException("bad").name()).isEqualTo("ForbiddenException");
            assertThat(new UnsupportedException("bad").name()).isEqualTo("UnsupportedException");
        }

        @Test
        void serialisesWithoutTheStack() {
            ValidationException error = ValidationException.field("cadence.count", "must be positive");
            assertThat(error.toSummary())
                    .isEqualTo(new ErrorSummary(
                            "validation_failed",
                            "cadence.count must be positive",
                            List.of(new ErrorDetail("cadence.count", "must be positive"))));
            assertThat(Arrays.stream(ErrorSummary.class.getRecordComponents()).map(RecordComponent::getName))
                    .containsExactly("code", "message", "details");
        }

        @Test
        void passesACauseThroughWhenOneIsGiven() {
            IllegalStateException cause = new IllegalStateException("disk went away");
            DomainException error = new DomainException(ErrorCode.CONFLICT, "write failed", cause);
            assertThat(error.getCause()).isSameAs(cause);
        }
    }

    @Nested
    class NotFound {

        @Test
        void namesTheResourceAndTheId() {
            NotFoundException error = new NotFoundException("habit", "hab_0123456789ABCDEF");
            assertThat(error.getMessage()).isEqualTo("habit hab_0123456789ABCDEF does not exist");
            assertThat(error.resource()).isEqualTo("habit");
            assertThat(error.id()).isEqualTo("hab_0123456789ABCDEF");
            assertThat(error.code()).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Nested
    class IsDomainException {

        @Test
        void acceptsEveryDomainFailureAndRejectsAnythingElse() {
            assertThat(DomainException.isDomainException(new NotFoundException("crew", "crw_0123456789ABCDEF")))
                    .isTrue();
            assertThat(DomainException.isDomainException(new IllegalStateException("plain")))
                    .isFalse();
            assertThat(DomainException.isDomainException("validation_failed")).isFalse();
            assertThat(DomainException.isDomainException(null)).isFalse();
        }
    }

    @Nested
    class Combine {

        @Test
        void returnsNothingWhenNothingFailed() {
            assertThat(ValidationException.combine("habit is invalid", List.of()))
                    .isEmpty();
        }

        @Test
        void mergesDetailsFromEveryFailure() {
            ValidationException combined = ValidationException.combine(
                            "habit is invalid",
                            List.of(
                                    ValidationException.field("name", "is required"),
                                    ValidationException.field("cadence", "is not recognised")))
                    .orElseThrow();
            assertThat(combined.details())
                    .containsExactly(
                            new ErrorDetail("name", "is required"), new ErrorDetail("cadence", "is not recognised"));
            assertThat(combined.getMessage()).isEqualTo("habit is invalid");
        }

        @Test
        void keepsADetailFreeFailureByPinningItToTheEmptyPath() {
            ValidationException combined = ValidationException.combine(
                            "habit is invalid", List.of(new ValidationException("nothing fits")))
                    .orElseThrow();
            assertThat(combined.details()).containsExactly(new ErrorDetail("", "nothing fits"));
        }
    }
}
