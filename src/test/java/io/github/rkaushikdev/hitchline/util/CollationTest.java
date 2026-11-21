package io.github.rkaushikdev.hitchline.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollationTest {

    private static List<String> sorted(String... names) {
        List<String> list = new ArrayList<>(List.of(names));
        list.sort(Collation.ORDER);
        return list;
    }

    @Test
    void ignoresCaseBeforeAnythingElse() {
        assertThat(sorted("banana", "Apple", "cherry")).containsExactly("Apple", "banana", "cherry");
    }

    @Test
    void putsLowerCaseBeforeUpperCaseWhenTheLettersAgree() {
        assertThat(sorted("Ada", "ada")).containsExactly("ada", "Ada");
    }

    @Test
    void putsAnUnaccentedLetterBeforeTheAccentedOne() {
        assertThat(sorted("résumé", "resume", "rest")).containsExactly("rest", "resume", "résumé");
    }

    @Test
    void countsSpacesAndPunctuationAndPutsThemFirst() {
        assertThat(sorted("Callhome", "Call home", "Call-home")).containsExactly("Call home", "Call-home", "Callhome");
    }

    @Test
    void putsDigitsBeforeLetters() {
        assertThat(sorted("a1", "1a", "aa")).containsExactly("1a", "a1", "aa");
    }

    @Test
    void readsAStrokedLetterAsItsBase() {
        assertThat(sorted("Bjørn", "Bjorn", "Bjp")).containsExactly("Bjorn", "Bjørn", "Bjp");
        assertThat(Collation.compare("Ada", "Ada")).isZero();
    }

    @Test
    void sortsAShorterPrefixFirst() {
        assertThat(sorted("Rows", "Row")).containsExactly("Row", "Rows");
        assertThat(Collation.fold("ÅSE")).isEqualTo("åse");
    }
}
