package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.MemberStatus;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgeChannel;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.MutableClock;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal format is unchanged, so an existing data directory loads. The fixture was written by
 * a separate JSON implementation in the journal's line format; the store must read every record
 * from it and write the same records back as the same bytes.
 */
class JournalFormatTest {

    private static final String ADA = "mem_0123456789ABCDEF";
    private static final String BO = "mem_FEDCBA9876543210";

    @TempDir
    Path directory;

    private static byte[] fixture() throws IOException {
        try (InputStream in = JournalFormatTest.class.getResourceAsStream("/journal/existing.jsonl")) {
            assertThat(in).as("the fixture journal is on the test class path").isNotNull();
            return in.readAllBytes();
        }
    }

    private Path copyOfFixture() throws IOException {
        Path path = directory.resolve("hitchline.jsonl");
        Files.write(path, fixture());
        return path;
    }

    @Test
    void loadsEveryRecordOfAnExistingJournal() throws IOException {
        Path path = copyOfFixture();
        try (FileStore store = FileStore.open(path, Clocks.systemClock(), IdSources.randomIds(), false)) {
            assertThat(store.members().all()).hasSize(2);
            CrewMember bo = store.members().byId(BO).orElseThrow();
            assertThat(bo.displayName()).isEqualTo("Bjørn Håland");
            assertThat(bo.status()).isEqualTo(MemberStatus.SUSPENDED);
            assertThat(bo.rotation().phase()).isEqualTo(14);

            Habit erg = store.habits().byId("hab_FEDCBA9876543210").orElseThrow();
            assertThat(erg.target().orElseThrow().format()).isEqualTo("at least 2.5 kilometres");
            assertThat(erg.status()).isEqualTo(HabitStatus.PAUSED);
            assertThat(erg.tags()).containsExactly("cardio", "fitness");
            assertThat(erg.graceDays()).isEqualTo(2);

            CheckIn voided = store.checkIns().byId("chk_0000000000000002").orElseThrow();
            assertThat(voided.reading()).hasValue(2.75);
            assertThat(voided.note()).isEqualTo("before \"breakfast\" \\ tired");
            assertThat(voided.voidedAt()).hasValue(Zones.parseInstant("2025-01-12T08:00:00.000Z"));
            assertThat(store.checkIns().byMember(ADA)).hasSize(2);

            assertThat(store.overrides().byMember(ADA).get(0).note()).isEqualTo("relief weathered off");
            Shield training = store.shields().byMember(ADA).get(0);
            assertThat(training.grantedBy()).hasValue(BO);
            assertThat(store.crews().all()).hasSize(1);
            assertThat(store.crews().memberships().get(0).role()).isEqualTo(CrewRole.LEAD);
            assertThat(store.sharing().byMember(ADA).orElseThrow().visibilityFor("hab_0123456789ABCDEF"))
                    .isEqualTo(Visibility.CREW);
            NudgePolicy policy = store.policies().byMember(ADA).orElseThrow();
            assertThat(policy.channel()).isEqualTo(NudgeChannel.EMAIL);
            assertThat(policy.roles()).containsExactly(DayRole.ONSHIFT, DayRole.OFFSHIFT);
        }
    }

    @Test
    void rewritesAnExistingJournalByteForByte() throws IOException {
        Path path = copyOfFixture();
        try (Journal journal = Journal.open(path)) {
            ReadReport report = journal.read();
            assertThat(report.entries()).hasSize(13);
            assertThat(report.skipped()).isZero();
            journal.rewrite(report.entries());
        }
        assertThat(Files.readString(path, StandardCharsets.UTF_8))
                .isEqualTo(new String(fixture(), StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(path)).isEqualTo(fixture());
    }

    @Test
    void writesTheSameLinesForTheSameRecords() throws IOException {
        Path source = copyOfFixture();
        List<JournalEntry> entries = Journal.read(source).entries();
        Path fresh = directory.resolve("fresh").resolve("hitchline.jsonl");
        MutableClock clock = Clocks.fixedClock("2025-01-10T09:00:00Z");
        try (FileStore store = FileStore.open(fresh, clock, IdSources.sequentialIds(), true)) {
            for (JournalEntry entry : entries) {
                clock.set(Zones.parseInstant(entry.at()));
                switch (entry.kind()) {
                    case MEMBER -> store.members().save(Codec.decodeMember(entry.row()));
                    case HABIT -> store.habits().save(Codec.decodeHabit(entry.row()));
                    case CHECKIN -> {
                        CheckIn checkIn = Codec.decodeCheckIn(entry.row());
                        if (store.checkIns().byId(checkIn.id()).isPresent()) {
                            store.checkIns().replace(checkIn);
                        } else {
                            store.checkIns().add(checkIn);
                        }
                    }
                    case OVERRIDE -> store.overrides().add(Codec.decodeOverride(entry.row()));
                    case SHIELD -> store.shields().add(Codec.decodeShield(entry.row()));
                    case CREW -> store.crews().save(Codec.decodeCrew(entry.row()));
                    case MEMBERSHIP -> store.crews().addMembership(Codec.decodeMembership(entry.row()));
                    case SHARING -> store.sharing().save(Codec.decodeSharing(entry.row()));
                    case POLICY -> {
                        Codec.StoredPolicy stored = Codec.decodePolicy(entry.row());
                        store.policies().save(stored.memberId(), stored.policy());
                    }
                }
            }
        }
        assertThat(Files.readString(fresh, StandardCharsets.UTF_8))
                .isEqualTo(new String(fixture(), StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(fresh)).isEqualTo(fixture());
    }
}
