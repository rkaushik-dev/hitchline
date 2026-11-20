package io.github.rkaushikdev.hitchline.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/**
 * JSON documents: the journal lines, backups, configuration files and response bodies. Reading
 * follows RFC 8259 strictly, nothing may follow the document, and a repeated key keeps its last
 * value. Writing is canonical: keys in the order they were added, numbers as {@link
 * Numbers#format} writes them, a non-finite number as {@code null}, and strings with only the
 * escapes RFC 8259 requires (control characters, quote, backslash and lone surrogates, in lower
 * case hex), so a document written twice is byte for byte the same.
 */
public final class Json {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {}

    /** The shared reader, for code that binds documents to types. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode array() {
        return JsonNodeFactory.instance.arrayNode();
    }

    /** Reads one document, refusing anything that is not JSON. */
    public static JsonNode parse(String text) {
        try {
            JsonNode tree = MAPPER.readTree(text);
            if (tree == null || tree.isMissingNode()) {
                throw new MalformedJsonException("no JSON document in the text");
            }
            return tree;
        } catch (JsonProcessingException malformed) {
            throw new MalformedJsonException(malformed.getOriginalMessage());
        }
    }

    /** The document on one line, in canonical form. */
    public static String stringify(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(out, value, "", "");
        return out.toString();
    }

    /** The document spread over lines, each level indented by {@code indent} spaces. */
    public static String pretty(JsonNode value, int indent) {
        StringBuilder out = new StringBuilder();
        write(out, value, " ".repeat(indent), "");
        return out.toString();
    }

    /** A string as a JSON string literal, quotes included. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        writeString(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, JsonNode value, String step, String margin) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            out.append("null");
        } else if (value.isTextual()) {
            writeString(out, value.textValue());
        } else if (value.isBoolean()) {
            out.append(value.booleanValue());
        } else if (value.isIntegralNumber()) {
            out.append(value.bigIntegerValue());
        } else if (value.isNumber()) {
            double number = value.doubleValue();
            out.append(Double.isNaN(number) || Double.isInfinite(number) ? "null" : Numbers.format(number));
        } else if (value.isArray()) {
            writeArray(out, value, step, margin);
        } else if (value.isObject()) {
            writeObject(out, value, step, margin);
        } else {
            writeString(out, value.asText());
        }
    }

    private static void writeArray(StringBuilder out, JsonNode value, String step, String margin) {
        if (value.isEmpty()) {
            out.append("[]");
            return;
        }
        String inner = margin + step;
        out.append('[');
        Iterator<JsonNode> items = value.elements();
        boolean first = true;
        while (items.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, step, inner);
            write(out, items.next(), step, inner);
        }
        newline(out, step, margin);
        out.append(']');
    }

    private static void writeObject(StringBuilder out, JsonNode value, String step, String margin) {
        if (value.isEmpty()) {
            out.append("{}");
            return;
        }
        String inner = margin + step;
        out.append('{');
        Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
        boolean first = true;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, step, inner);
            writeString(out, field.getKey());
            out.append(step.isEmpty() ? ":" : ": ");
            write(out, field.getValue(), step, inner);
        }
        newline(out, step, margin);
        out.append('}');
    }

    private static void newline(StringBuilder out, String step, String margin) {
        if (!step.isEmpty()) {
            out.append('\n').append(margin);
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index += 1) {
            char unit = value.charAt(index);
            switch (unit) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (unit < 0x20 || isLoneSurrogate(value, index)) {
                        out.append(String.format("\\u%04x", (int) unit));
                    } else {
                        out.append(unit);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean isLoneSurrogate(String value, int index) {
        char unit = value.charAt(index);
        if (Character.isHighSurrogate(unit)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(unit)) {
            return index == 0 || !Character.isHighSurrogate(value.charAt(index - 1));
        }
        return false;
    }
}
