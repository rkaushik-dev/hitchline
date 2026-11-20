package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PortsTest {

    /** Something that can be flushed but not closed, which is not enough to count. */
    private static final class FlushOnly {
        void flush() {}
    }

    @Nested
    class IsDurable {

        @Test
        void acceptsSomethingWithBothMethods() {
            Durable durable = new Durable() {
                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
            assertThat(Durable.isDurable(durable)).isTrue();
        }

        @Test
        void rejectsSomethingWithOnlyOne() {
            assertThat(Durable.isDurable(new FlushOnly())).isFalse();
        }

        @Test
        void rejectsAPlainValue() {
            assertThat(Durable.isDurable(null)).isFalse();
            assertThat(Durable.isDurable("store")).isFalse();
            assertThat(Durable.isDurable(MemoryStore.create())).isFalse();
        }
    }

    @Nested
    class ThePortShapes {

        @Test
        void letsAHandRolledClockStandIn() {
            Clock clock = () -> Instant.ofEpochMilli(1_736_500_000_000L);
            assertThat(clock.now().toEpochMilli()).isEqualTo(1_736_500_000_000L);
        }

        @Test
        void letsAHandRolledIdSourceStandIn() {
            IdSource ids = kind -> Ids.mintId(kind, new byte[16]);
            assertThat(ids.next(IdKind.HABIT)).isEqualTo("hab_0000000000000000");
            assertThat(ids.next(IdKind.CREW)).isEqualTo("crw_0000000000000000");
        }

        @Test
        void collectsTheWholeSetBehindOneType() {
            Ports set = PortSet.copyOf(MemoryStore.create()).withClock(() -> Instant.EPOCH);
            assertThat(set.clock().now().toEpochMilli()).isZero();
            assertThat(set.withEvents(EventBus.create()).events()).isPresent();
        }
    }
}
