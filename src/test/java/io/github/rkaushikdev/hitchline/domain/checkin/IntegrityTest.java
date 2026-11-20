package io.github.rkaushikdev.hitchline.domain.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IntegrityTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private static CheckIn entry(String id, String at, OptionalDouble reading) {
        return CheckIn.builder(
                        Ids.checkInId(id),
                        TREADMILL,
                        ADA,
                        Zones.parseInstant(at),
                        Zones.parseInstant(at),
                        CheckInSource.APP)
                .reading(reading)
                .build();
    }

    private static final CheckIn FIRST = entry("chk_0000000000000001", "2025-01-10T07:00:00Z", OptionalDouble.of(20));
    private static final CheckIn SECOND = entry("chk_0000000000000002", "2025-01-11T07:00:00Z", OptionalDouble.of(25));
    private static final CheckIn THIRD = entry("chk_0000000000000003", "2025-01-12T07:00:00Z", OptionalDouble.empty());

    @Nested
    class Digests {

        @Test
        void isALowerCaseSha256InHex() {
            assertThat(Integrity.digestOf(FIRST, Integrity.GENESIS)).matches("[0-9a-f]{64}");
        }

        @Test
        void isStableForTheSameEntryAndPredecessor() {
            assertThat(Integrity.digestOf(FIRST, Integrity.GENESIS))
                    .isEqualTo(Integrity.digestOf(FIRST, Integrity.GENESIS));
        }

        @Test
        void changesWhenAnyFieldChanges() {
            String base = Integrity.digestOf(FIRST, Integrity.GENESIS);
            assertThat(Integrity.digestOf(FIRST.withReading(OptionalDouble.of(21)), Integrity.GENESIS))
                    .isNotEqualTo(base);
            assertThat(Integrity.digestOf(FIRST.withNote("edited"), Integrity.GENESIS))
                    .isNotEqualTo(base);
            assertThat(Integrity.digestOf(FIRST.withSource(CheckInSource.KIOSK), Integrity.GENESIS))
                    .isNotEqualTo(base);
        }

        @Test
        void changesWhenThePredecessorChanges() {
            assertThat(Integrity.digestOf(SECOND, Integrity.digestOf(FIRST, Integrity.GENESIS)))
                    .isNotEqualTo(Integrity.digestOf(SECOND, Integrity.GENESIS));
        }

        @Test
        void refusesAPredecessorThatIsNotADigest() {
            assertThatThrownBy(() -> Integrity.digestOf(FIRST, "nope")).isInstanceOf(ValidationException.class);
            String upper = Integrity.digestOf(FIRST, Integrity.GENESIS).toUpperCase(Locale.ROOT);
            assertThatThrownBy(() -> Integrity.digestOf(FIRST, upper)).hasMessageContaining("lower case hex");
        }

        @Test
        void matchesDigestsComputedOutsideThisCode() {
            // SHA-256 over "previous U+001E fields joined by U+001F", computed with an independent
            // implementation. Digests already recorded against a ledger must keep verifying.
            List<ChainLink> links = Integrity.chainOf(List.of(FIRST, SECOND, THIRD));
            assertThat(links.stream().map(ChainLink::digest))
                    .containsExactly(
                            "8c5d046888183bb9280ca93b0546e4057125d2d4ca85bf07bfbd3436e321fb96",
                            "6a0a9dc130eacd68e52e2116823798c2601a76d9516914af2bcc055ca9731123",
                            "580e955c72d420c70ea97b9c4434a3fba225d48ec68401d87c1886579db0917f");
            CheckIn everyField = CheckIn.builder(
                            FIRST.id(), TREADMILL, ADA, FIRST.at(), FIRST.recordedAt(), CheckInSource.KIOSK)
                    .reading(2.5)
                    .note("café at dawn")
                    .idempotencyKey("watch-2025-01-10")
                    .build()
                    .voidAt(Zones.parseInstant("2025-01-20T09:00:00Z"));
            assertThat(Integrity.digestOf(everyField, Integrity.GENESIS))
                    .isEqualTo("7d7d025ef81667f22294edefe2d4e2489bd5e28fde3cced082753ea9f732bf46");
        }
    }

    @Nested
    class TheChain {

        @Test
        void linksEveryEntryInEventOrder() {
            assertThat(Integrity.chainOf(List.of(THIRD, FIRST, SECOND)).stream()
                            .map(link -> link.entry().id()))
                    .containsExactly("chk_0000000000000001", "chk_0000000000000002", "chk_0000000000000003");
        }

        @Test
        void foldsEachDigestOntoTheOneBeforeIt() {
            List<ChainLink> links = Integrity.chainOf(List.of(FIRST, SECOND));
            assertThat(links.get(0).digest()).isEqualTo(Integrity.digestOf(FIRST, Integrity.GENESIS));
            assertThat(links.get(1).digest())
                    .isEqualTo(Integrity.digestOf(SECOND, links.get(0).digest()));
        }

        @Test
        void readsAnEmptyLedgerAsTheGenesisDigest() {
            assertThat(Integrity.chainOf(List.of())).isEmpty();
            assertThat(Integrity.headOf(List.of())).isEqualTo(Integrity.GENESIS);
        }

        @Test
        void answersTheLastDigestAsTheHead() {
            List<ChainLink> links = Integrity.chainOf(List.of(FIRST, SECOND));
            assertThat(Integrity.headOf(List.of(FIRST, SECOND)))
                    .isEqualTo(links.get(1).digest());
        }
    }

    @Nested
    class Verifying {

        private final List<CheckIn> entries = List.of(FIRST, SECOND, THIRD);
        private final List<String> recorded =
                Integrity.chainOf(entries).stream().map(ChainLink::digest).toList();

        @Test
        void passesOnAnUntouchedLedger() {
            ChainCheck check = Integrity.verifyChain(entries, recorded);
            assertThat(check.ok()).isTrue();
            assertThat(check.checked()).isEqualTo(3);
            assertThat(check.describe()).isEqualTo("3 entries verified");
        }

        @Test
        void namesTheFirstEntryThatWasEdited() {
            ChainCheck check =
                    Integrity.verifyChain(List.of(FIRST, SECOND.withReading(OptionalDouble.of(99)), THIRD), recorded);
            assertThat(check.ok()).isFalse();
            assertThat(check.brokenAt()).hasValue("chk_0000000000000002");
            assertThat(check.checked()).isEqualTo(2);
        }

        @Test
        void spotsAnEntryThatWasRemoved() {
            assertThat(Integrity.verifyChain(List.of(FIRST, SECOND), recorded).ok())
                    .isFalse();
        }

        @Test
        void spotsAnEntryThatWasAdded() {
            CheckIn extra = entry("chk_0000000000000004", "2025-01-13T07:00:00Z", OptionalDouble.empty());
            ChainCheck check = Integrity.verifyChain(List.of(FIRST, SECOND, THIRD, extra), recorded);
            assertThat(check.ok()).isFalse();
            assertThat(check.brokenAt()).hasValue("chk_0000000000000004");
        }

        @Test
        void spotsAVoidAppliedAfterTheFact() {
            CheckIn voided = SECOND.voidAt(Zones.parseInstant("2025-01-20T09:00:00Z"));
            ChainCheck check = Integrity.verifyChain(List.of(FIRST, voided, THIRD), recorded);
            assertThat(check.ok()).isFalse();
            assertThat(check.brokenAt()).hasValue("chk_0000000000000002");
        }

        @Test
        void describesABreak() {
            ChainCheck check = Integrity.verifyChain(List.of(FIRST, SECOND.withNote("edited"), THIRD), recorded);
            assertThat(check.describe()).isEqualTo("broke at chk_0000000000000002 after 2 entries");
        }
    }
}
