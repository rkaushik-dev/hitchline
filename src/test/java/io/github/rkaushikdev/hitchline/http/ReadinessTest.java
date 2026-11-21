package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.MemberRepository;
import io.github.rkaushikdev.hitchline.app.PortSet;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.http.Readiness.CheckResult;
import io.github.rkaushikdev.hitchline.http.Readiness.CheckState;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReadinessTest {

    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);

    private static MemoryStore aStore() {
        return MemoryStore.create(Clocks.fixedClock("2025-06-19T09:00:00Z"), IdSources.randomIds());
    }

    private static DoubleSupplier ticking(double... steps) {
        int[] index = {0};
        return () -> {
            double value = steps[Math.min(index[0], steps.length - 1)];
            index[0] += 1;
            return value;
        };
    }

    private static CheckResult check(String name, CheckState state) {
        return new CheckResult(name, state, "", 0);
    }

    @Nested
    class RollingUpStates {

        @Test
        void takesTheWorstOfThem() {
            assertThat(Readiness.worstOf(List.of())).isEqualTo(CheckState.PASS);
            assertThat(Readiness.worstOf(List.of(check("a", CheckState.PASS)))).isEqualTo(CheckState.PASS);
            assertThat(Readiness.worstOf(List.of(check("a", CheckState.PASS), check("b", CheckState.WARN))))
                    .isEqualTo(CheckState.WARN);
            assertThat(Readiness.worstOf(List.of(check("a", CheckState.WARN), check("b", CheckState.FAIL))))
                    .isEqualTo(CheckState.FAIL);
        }

        @Test
        void answers503OnlyWhenSomethingFailed() {
            assertThat(Readiness.statusFor(new Readiness(CheckState.PASS, List.of())))
                    .isEqualTo(200);
            assertThat(Readiness.statusFor(new Readiness(CheckState.WARN, List.of())))
                    .isEqualTo(200);
            assertThat(Readiness.statusFor(new Readiness(CheckState.FAIL, List.of())))
                    .isEqualTo(503);
        }
    }

    @Nested
    class ReadingReadiness {

        @Test
        void passesOnAHealthyStore() {
            MemoryStore ports = aStore();
            ports.members().save(Fixtures.aMember());
            Readiness report = Readiness.read(ports, CONFIG, () -> 0, Readiness.DEFAULT_SLOW_MS);
            assertThat(report.state()).isEqualTo(CheckState.PASS);
            assertThat(report.checks().stream().map(CheckResult::name)).containsExactly("store", "clock", "durability");
            assertThat(report.checks().get(0).detail()).isEqualTo("1 members");
        }

        @Test
        void warnsWhenTheStoreIsSlow() {
            Readiness report = Readiness.read(aStore(), CONFIG, ticking(0, 400), 250);
            assertThat(report.checks().get(0).state()).isEqualTo(CheckState.WARN);
            assertThat(report.state()).isEqualTo(CheckState.WARN);
            assertThat(Readiness.statusFor(report)).isEqualTo(200);
        }

        @Test
        void failsWhenTheStoreThrows() {
            MemberRepository broken = new MemberRepository() {
                @Override
                public Optional<CrewMember> byId(String id) {
                    return Optional.empty();
                }

                @Override
                public List<CrewMember> all() {
                    throw new IllegalStateException("disk went away");
                }

                @Override
                public void save(CrewMember member) {}
            };
            PortSet set = PortSet.copyOf(aStore());
            Ports ports = new PortSet(
                    set.events(),
                    set.clock(),
                    set.ids(),
                    broken,
                    set.habits(),
                    set.checkIns(),
                    set.overrides(),
                    set.shields(),
                    set.crews(),
                    set.sharing(),
                    set.policies());
            Readiness report = Readiness.read(ports, CONFIG, () -> 0, Readiness.DEFAULT_SLOW_MS);
            assertThat(report.state()).isEqualTo(CheckState.FAIL);
            assertThat(report.checks().get(0).detail()).isEqualTo("disk went away");
            assertThat(Readiness.statusFor(report)).isEqualTo(503);
        }

        @Test
        void failsWhenTheClockReadsNothing() {
            Ports broken = aStore().withClock(() -> null);
            Readiness report = Readiness.read(broken, CONFIG, () -> 0, Readiness.DEFAULT_SLOW_MS);
            assertThat(report.checks().get(1).state()).isEqualTo(CheckState.FAIL);
            assertThat(report.checks().get(1).detail()).isEqualTo("the clock read nothing");
        }

        @Test
        void saysWhichStoreItIsRunningOn() {
            MemoryStore ports = aStore();
            Readiness memory = Readiness.read(ports, CONFIG, () -> 0, Readiness.DEFAULT_SLOW_MS);
            assertThat(memory.checks().get(2).detail()).isEqualTo("memory only");

            Readiness onDisk =
                    Readiness.read(ports, CONFIG.withStore(StoreKind.FILE), () -> 0, Readiness.DEFAULT_SLOW_MS);
            assertThat(onDisk.checks().get(2).state()).isEqualTo(CheckState.WARN);
            assertThat(onDisk.checks().get(2).detail()).isEqualTo("journal on disk");
        }
    }
}
