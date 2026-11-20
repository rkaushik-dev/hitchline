package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocksTest {

    private static final LockOwner OWNER = new LockOwner(4242, "grane-b", "2025-05-19T09:00:00.000Z");
    private static final long NOW_MS =
            Zones.parseInstant("2025-05-19T09:00:30.000Z").toEpochMilli();

    @TempDir
    Path directory;

    private Path target;

    @BeforeEach
    void place() {
        target = directory.resolve("nested").resolve("hitchline.jsonl");
    }

    private LockHolder take(LockOwner owner, long nowMs) {
        return Locks.takeLock(target, owner, Locks.DEFAULT_STALE_MS, nowMs);
    }

    @Nested
    class TheLockPath {

        @Test
        void sitsBesideTheFileItGuards() {
            assertThat(Locks.lockPathFor(Path.of("/data/store.jsonl"))).isEqualTo(Path.of("/data/store.jsonl.lock"));
        }

        @Test
        void refusesAnEmptyTarget() {
            assertThatThrownBy(() -> Locks.lockPathFor(Path.of("  "))).isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class TakingIt {

        @Test
        void writesALockNamingTheHolder() {
            LockHolder held = take(OWNER, NOW_MS);
            assertThat(Locks.readLock(target)).hasValue(OWNER);
            assertThat(held.path().toString()).endsWith(".lock");
            held.release();
        }

        @Test
        void createsTheDirectoryItNeeds() throws IOException {
            LockHolder held = take(OWNER, NOW_MS);
            assertThat(Files.readString(held.path(), StandardCharsets.UTF_8)).contains("grane-b");
            held.release();
        }

        @Test
        void refusesWhenSomebodyElseHoldsIt() {
            LockHolder held = take(OWNER, NOW_MS);
            assertThatThrownBy(() -> take(new LockOwner(99, OWNER.host(), OWNER.at()), NOW_MS))
                    .isInstanceOf(ConflictException.class);
            held.release();
        }

        @Test
        void namesTheHolderInTheRefusal() {
            LockHolder held = take(OWNER, NOW_MS);
            assertThatThrownBy(() -> take(OWNER, NOW_MS)).hasMessageContaining("pid 4242 on grane-b");
            held.release();
        }

        @Test
        void canBeTakenAgainOnceReleased() {
            take(OWNER, NOW_MS).release();
            LockHolder second = take(OWNER, NOW_MS);
            assertThat(second.heldBy().pid()).isEqualTo(4242);
            second.release();
        }

        @Test
        void readsNoLockWhenThereIsNone() {
            assertThat(Locks.readLock(target)).isEmpty();
        }
    }

    @Nested
    class AStaleLock {

        @Test
        void isTakenOverOnceItIsOldEnough() {
            take(OWNER, NOW_MS);
            long later = Zones.parseInstant("2025-05-19T09:30:00.000Z").toEpochMilli();
            LockHolder second = take(new LockOwner(99, OWNER.host(), "2025-05-19T09:30:00.000Z"), later);
            assertThat(Locks.readLock(target).map(LockOwner::pid)).hasValue(99L);
            second.release();
        }

        @Test
        void isLeftAloneInsideTheStaleWindow() {
            LockHolder held = Locks.takeLock(target, OWNER, 60 * 60_000L, NOW_MS);
            long later = Zones.parseInstant("2025-05-19T09:30:00.000Z").toEpochMilli();
            assertThatThrownBy(() -> Locks.takeLock(target, OWNER, 60 * 60_000L, later))
                    .isInstanceOf(ConflictException.class);
            held.release();
        }

        @Test
        void takesOverALockFileNobodyCanRead() throws IOException {
            Files.createDirectories(target.getParent());
            Files.writeString(Locks.lockPathFor(target), "not json at all", StandardCharsets.UTF_8);
            LockHolder held = take(OWNER, NOW_MS);
            assertThat(Locks.readLock(target).map(LockOwner::pid)).hasValue(4242L);
            held.release();
        }
    }

    @Nested
    class TheOwnerHere {

        @Test
        void namesThisProcess() {
            LockOwner here = LockOwner.here("2025-05-19T09:00:00.000Z");
            assertThat(here.pid()).isEqualTo(ProcessHandle.current().pid());
            assertThat(here.at()).isEqualTo("2025-05-19T09:00:00.000Z");
            assertThat(here.host()).isNotEmpty();
        }
    }
}
