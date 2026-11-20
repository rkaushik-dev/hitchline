package io.github.rkaushikdev.hitchline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IdsTest {

    private static final String GOOD_SUFFIX = "0123456789ABCDEF";

    @Nested
    class Parsing {

        @Test
        void acceptsAWellFormedIdForEachKind() {
            assertThat(Ids.crewMemberId("mem_" + GOOD_SUFFIX)).isEqualTo("mem_" + GOOD_SUFFIX);
            assertThat(Ids.crewId("crw_" + GOOD_SUFFIX)).isEqualTo("crw_" + GOOD_SUFFIX);
            assertThat(Ids.habitId("hab_" + GOOD_SUFFIX)).isEqualTo("hab_" + GOOD_SUFFIX);
            assertThat(Ids.checkInId("chk_" + GOOD_SUFFIX)).isEqualTo("chk_" + GOOD_SUFFIX);
        }

        @Test
        void rejectsAnIdCarryingAnotherKindOfPrefix() {
            assertThatThrownBy(() -> Ids.habitId("crw_" + GOOD_SUFFIX)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> Ids.habitId("crw_" + GOOD_SUFFIX)).hasMessageContaining("must start with hab_");
        }

        @Test
        void rejectsAnIdWithNoSeparatorAtAll() {
            assertThatThrownBy(() -> Ids.crewId("crw" + GOOD_SUFFIX)).hasMessageContaining("must look like crw_");
        }

        @Test
        void rejectsTheLettersLeftOutOfTheAlphabet() {
            assertThatThrownBy(() -> Ids.crewId("crw_ILOU456789ABCDEF")).hasMessageContaining("unreadable suffix");
        }

        @Test
        void rejectsASuffixOfTheWrongLength() {
            assertThatThrownBy(() -> Ids.crewId("crw_0123")).hasMessageContaining("unreadable suffix");
            assertThatThrownBy(() -> Ids.crewId("crw_" + GOOD_SUFFIX + "0")).hasMessageContaining("unreadable suffix");
        }
    }

    @Nested
    class IsWellFormed {

        @Test
        void answersWithoutThrowing() {
            assertThat(Ids.isWellFormed(IdKind.HABIT, "hab_" + GOOD_SUFFIX)).isTrue();
            assertThat(Ids.isWellFormed(IdKind.HABIT, "hab_lowercase123456")).isFalse();
            assertThat(Ids.isWellFormed(IdKind.CREW, "")).isFalse();
        }
    }

    @Nested
    class MintId {

        @Test
        void buildsAParseableIdFromSuppliedBytes() {
            byte[] bytes = new byte[16];
            for (int index = 0; index < bytes.length; index += 1) {
                bytes[index] = (byte) index;
            }
            String minted = Ids.mintId(IdKind.HABIT, bytes);
            assertThat(minted).isEqualTo("hab_0123456789ABCDEF");
            assertThat(Ids.isWellFormed(IdKind.HABIT, minted)).isTrue();
        }

        @Test
        void wrapsByteValuesLargerThanTheAlphabet() {
            byte[] bytes = new byte[16];
            Arrays.fill(bytes, (byte) 33);
            assertThat(Ids.mintId(IdKind.CREW, bytes)).isEqualTo("crw_1111111111111111");
        }

        @Test
        void refusesToMintFromTooFewBytes() {
            assertThatThrownBy(() -> Ids.mintId(IdKind.CREW, new byte[4]))
                    .hasMessageContaining("needs at least 16 entries");
        }

        @Test
        void exposesThePrefixUsedForAKind() {
            assertThat(Ids.idPrefix(IdKind.CHECK_IN)).isEqualTo("chk");
        }
    }
}
