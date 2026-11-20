package io.github.rkaushikdev.hitchline.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NumbersTest {

    @Nested
    class Format {

        @Test
        void writesAWholeNumberWithoutAPoint() {
            assertThat(Numbers.format(3.0)).isEqualTo("3");
            assertThat(Numbers.format(-42.0)).isEqualTo("-42");
            assertThat(Numbers.format(-0.0)).isEqualTo("0");
            assertThat(Numbers.format(123_456_789_012L)).isEqualTo("123456789012");
        }

        @Test
        void writesTheShortestDecimalThatReadsBack() {
            assertThat(Numbers.format(0.1 + 0.2)).isEqualTo("0.30000000000000004");
            assertThat(Numbers.format(0.25)).isEqualTo("0.25");
            assertThat(Numbers.format(2.0 / 3)).isEqualTo("0.6666666666666666");
            assertThat(Numbers.format(1e20)).isEqualTo("100000000000000000000");
        }

        @Test
        void usesAnExponentOnlyAtTheExtremes() {
            assertThat(Numbers.format(1e21)).isEqualTo("1e+21");
            assertThat(Numbers.format(1.5e-7)).isEqualTo("1.5e-7");
            assertThat(Numbers.format(1e-7)).isEqualTo("1e-7");
            assertThat(Numbers.format(0.000001)).isEqualTo("0.000001");
        }

        @Test
        void namesTheValuesThatAreNotNumbers() {
            assertThat(Numbers.format(Double.NaN)).isEqualTo("NaN");
            assertThat(Numbers.format(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
            assertThat(Numbers.format(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
        }
    }

    @Nested
    class ToFixed {

        @Test
        void roundsTheExactBinaryValue() {
            assertThat(Numbers.toFixed(1.005, 2)).isEqualTo("1.00");
            assertThat(Numbers.toFixed(2.5, 0)).isEqualTo("3");
            assertThat(Numbers.toFixed(0.125, 2)).isEqualTo("0.13");
        }

        @Test
        void padsWithZeros() {
            assertThat(Numbers.toFixed(1, 3)).isEqualTo("1.000");
            assertThat(Numbers.toFixed(0.5, 1)).isEqualTo("0.5");
        }

        @Test
        void keepsTheSignOfANegativeValue() {
            assertThat(Numbers.toFixed(-2.5, 0)).isEqualTo("-3");
            assertThat(Numbers.toFixed(-0.001, 2)).isEqualTo("-0.00");
            assertThat(Numbers.toFixed(-0.0, 2)).isEqualTo("0.00");
        }

        @Test
        void refusesAnImpossibleNumberOfPlaces() {
            assertThatThrownBy(() -> Numbers.toFixed(1, -1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Round {

        @Test
        void sendsATieTowardsPositiveInfinity() {
            assertThat(Numbers.round(2.5)).isEqualTo(3);
            assertThat(Numbers.round(-2.5)).isEqualTo(-2);
            assertThat(Numbers.round(2.4)).isEqualTo(2);
            assertThat(Numbers.roundTo(0.125, 2)).isEqualTo(0.13);
            assertThat(Numbers.round(0.49999999999999994)).isZero();
        }
    }
}
