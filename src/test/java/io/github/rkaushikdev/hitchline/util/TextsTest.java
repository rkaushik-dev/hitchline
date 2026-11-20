package io.github.rkaushikdev.hitchline.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void splitKeepsEveryPartIncludingTrailingEmptyOnes() {
        assertThat(Texts.split("a,,b,", ",")).containsExactly("a", "", "b", "");
        assertThat(Texts.split("2025-01-01..2025-01-02", "..")).containsExactly("2025-01-01", "2025-01-02");
        assertThat(Texts.split("", ",")).containsExactly("");
        assertThat(Texts.split("ab", "")).containsExactly("a", "b");
    }

    @Test
    void trimRemovesUnicodeWhiteSpaceIncludingNoBreakSpace() {
        assertThat(Texts.trim("  ada\t\n")).isEqualTo("ada");
        assertThat(Texts.trim("﻿ bo　")).isEqualTo("bo");
        assertThat(Texts.trim("   ")).isEmpty();
        assertThat(Texts.isSpace('x')).isFalse();
    }

    @Test
    void padsOnEitherSide() {
        assertThat(Texts.padStart("7", 3, '0')).isEqualTo("007");
        assertThat(Texts.padEnd("ab", 4, '.')).isEqualTo("ab..");
        assertThat(Texts.padStart("long", 2, ' ')).isEqualTo("long");
        assertThat(Texts.padEnd("long", 2, ' ')).isEqualTo("long");
        assertThat(Texts.twoDigits(5)).isEqualTo("05");
    }
}
