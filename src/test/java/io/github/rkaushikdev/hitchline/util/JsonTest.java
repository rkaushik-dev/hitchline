package io.github.rkaushikdev.hitchline.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesKeysInTheOrderTheyWereAdded() {
        ObjectNode row = Json.object();
        row.put("seq", 1).put("kind", "member").put("at", "2025-01-10T09:00:00.000Z");
        assertThat(Json.stringify(row))
                .isEqualTo("{\"seq\":1,\"kind\":\"member\",\"at\":\"2025-01-10T09:00:00.000Z\"}");
    }

    @Test
    void writesNumbersWithoutATrailingPoint() {
        ObjectNode row = Json.object()
                .put("whole", 20.0)
                .put("half", 2.5)
                .put("tiny", 1.5e-7)
                .put("nan", Double.NaN);
        assertThat(Json.stringify(row)).isEqualTo("{\"whole\":20,\"half\":2.5,\"tiny\":1.5e-7,\"nan\":null}");
    }

    @Test
    void escapesOnlyWhatTheStandardRequires() {
        assertThat(Json.quote("a\"b\\c\nd\u0001é\u2028")).isEqualTo("\"a\\\"b\\\\c\\nd\\u0001é\u2028\"");
        assertThat(Json.quote("\ud800x")).isEqualTo("\"\\ud800x\"");
        assertThat(Json.quote("\ud83d\ude00")).isEqualTo("\"\ud83d\ude00\"");
    }

    @Test
    void spreadsADocumentOverLinesWhenAskedTo() {
        ObjectNode backup = Json.object();
        backup.put("version", 1);
        backup.putArray("rows").add(Json.object().put("id", "a"));
        backup.putArray("empty");
        backup.putObject("nothing");
        assertThat(Json.pretty(backup, 2))
                .isEqualTo("{\n  \"version\": 1,\n  \"rows\": [\n    {\n      \"id\": \"a\"\n    }\n  ],\n"
                        + "  \"empty\": [],\n  \"nothing\": {}\n}");
    }

    @Test
    void readsBackWhatItWrote() {
        String text = "{\"a\":[1,2.5,\"x\",true,null],\"b\":{}}";
        assertThat(Json.stringify(Json.parse(text))).isEqualTo(text);
    }

    @Test
    void refusesTextThatIsNotOneDocument() {
        assertThatThrownBy(() -> Json.parse("nope")).isInstanceOf(MalformedJsonException.class);
        assertThatThrownBy(() -> Json.parse("{} {}")).isInstanceOf(MalformedJsonException.class);
        assertThatThrownBy(() -> Json.parse("")).isInstanceOf(MalformedJsonException.class);
    }

    @Test
    void readsNumberTextTheWaySettingsAreRead() {
        assertThat(Numbers.parse(" 9000 ")).isEqualTo(9000.0);
        assertThat(Numbers.parse("")).isEqualTo(0.0);
        assertThat(Numbers.parse("1e3")).isEqualTo(1000.0);
        assertThat(Numbers.parse("0x1F")).isEqualTo(31.0);
        assertThat(Numbers.parse("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(Numbers.parse("eighty")).isNaN();
        assertThat(Numbers.parse("0x1G")).isNaN();
        assertThat(Numbers.isInteger(9000.0)).isTrue();
        assertThat(Numbers.isInteger(1.5)).isFalse();
        assertThat(Numbers.isInteger(Double.NaN)).isFalse();
    }
}
