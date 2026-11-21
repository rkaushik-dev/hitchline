package io.github.rkaushikdev.hitchline.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.domain.ErrorDetail;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The shape one value arriving over http must have, and what it reads as. A schema only checks
 * shape: a JSON type, a length, a range, a pattern, one of a set of words. Every problem it finds is
 * reported against the path of the value that had it, and a value with problems reads as nothing.
 *
 * @param <T> what a well formed value reads as
 */
@FunctionalInterface
public interface Schema<T> {

    /**
     * Reads a value that is present (JSON null included).
     *
     * @param value the value
     * @param path where it sits, such as {@code items.0.habitId}; empty for the whole body
     * @param problems where each problem found is added
     * @return what it reads as, or nothing when a problem was found
     */
    Optional<T> read(JsonNode value, String path, List<ErrorDetail> problems);

    /** What this reads as, passed through {@code change}. */
    default <R> Schema<R> map(Function<T, R> change) {
        return (value, path, problems) -> read(value, path, problems).map(change);
    }

    /** Text, with no rule on its length or content yet. */
    static Text text() {
        return new Text(0, Integer.MAX_VALUE, List.of(), false);
    }

    /** A JSON number, with no rule on its range yet. */
    static Num number() {
        return new Num(false, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /** One of the words of {@code type}. */
    static <E extends Enum<E> & WireValue> Schema<E> oneOf(Class<E> type) {
        String expected = Arrays.stream(type.getEnumConstants())
                .map(value -> "'" + value.wire() + "'")
                .collect(Collectors.joining(" | "));
        return (value, path, problems) -> {
            if (!value.isTextual()) {
                problems.add(new ErrorDetail(path, "Expected " + expected + ", received " + typeOf(value)));
                return Optional.empty();
            }
            Optional<E> found = WireValue.fromWire(type, value.asText());
            if (found.isEmpty()) {
                problems.add(new ErrorDetail(
                        path, "Invalid enum value. Expected " + expected + ", received '" + value.asText() + "'"));
            }
            return found;
        };
    }

    /** A JSON array of {@code item}, between {@code min} and {@code max} long. */
    static <T> Schema<List<T>> array(Schema<T> item, int min, int max) {
        return (value, path, problems) -> {
            if (!value.isArray()) {
                problems.add(new ErrorDetail(path, "Expected array, received " + typeOf(value)));
                return Optional.empty();
            }
            int before = problems.size();
            List<T> read = new ArrayList<>();
            for (int index = 0; index < value.size(); index += 1) {
                item.read(value.get(index), join(path, String.valueOf(index)), problems)
                        .ifPresent(read::add);
            }
            if (value.size() < min) {
                problems.add(new ErrorDetail(path, "Array must contain at least " + min + " element(s)"));
            }
            if (value.size() > max) {
                problems.add(new ErrorDetail(path, "Array must contain at most " + max + " element(s)"));
            }
            return problems.size() > before ? Optional.empty() : Optional.of(List.copyOf(read));
        };
    }

    /** A JSON object read field by field by {@code build}; keys it does not ask for are ignored. */
    static <T> Schema<T> object(Function<Fields, T> build) {
        return object(build, fields -> Optional.empty());
    }

    /**
     * A JSON object read field by field by {@code build}, then checked as a whole by {@code refine},
     * which answers the problem with the whole object, if there is one. The whole is checked only
     * once every field is well formed.
     */
    static <T> Schema<T> object(Function<Fields, T> build, Function<Fields, Optional<String>> refine) {
        return (value, path, problems) -> {
            if (!value.isObject()) {
                problems.add(new ErrorDetail(path, "Expected object, received " + typeOf(value)));
                return Optional.empty();
            }
            int before = problems.size();
            Fields fields = new Fields((ObjectNode) value, path, problems);
            T built = build.apply(fields);
            if (problems.size() > before) {
                return Optional.empty();
            }
            Optional<String> refused = refine.apply(fields);
            refused.ifPresent(message -> problems.add(new ErrorDetail(path, message)));
            return refused.isPresent() ? Optional.empty() : Optional.of(built);
        };
    }

    /** The JSON type of a value as a problem names it. */
    static String typeOf(JsonNode value) {
        if (value.isNull()) {
            return "null";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return value.isArray() ? "array" : "object";
    }

    /** The path of {@code name} inside {@code path}. */
    static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    /**
     * The fields of one JSON object being read. A field asked for and absent is a problem when it is
     * required and nothing when it is optional; a field that is present but malformed is a problem
     * either way, and reads as nothing (or null, for a required one, in a body that is then thrown
     * away).
     */
    final class Fields {

        private final ObjectNode source;
        private final String path;
        private final List<ErrorDetail> problems;
        private int given;

        Fields(ObjectNode source, String path, List<ErrorDetail> problems) {
            this.source = source;
            this.path = path;
            this.problems = problems;
        }

        private JsonNode present(String name) {
            JsonNode value = source.get(name);
            if (value != null) {
                given += 1;
            }
            return value;
        }

        /** True when the object holds {@code name}, null included. */
        public boolean has(String name) {
            return source.has(name);
        }

        /** How many of the fields asked for so far the object held. */
        public int given() {
            return given;
        }

        /** The field {@code name}, which must be present and well formed. */
        public <T> T required(String name, Schema<T> schema) {
            JsonNode value = present(name);
            if (value == null) {
                problems.add(new ErrorDetail(join(path, name), "Required"));
                return null;
            }
            return schema.read(value, join(path, name), problems).orElse(null);
        }

        /** The field {@code name} when present; nothing when absent or malformed. */
        public <T> Optional<T> optional(String name, Schema<T> schema) {
            JsonNode value = present(name);
            return value == null ? Optional.empty() : schema.read(value, join(path, name), problems);
        }

        /** The field {@code name}, which may be absent or null. */
        public <T> Optional<T> nullable(String name, Schema<T> schema) {
            JsonNode value = present(name);
            return value == null || value.isNull() ? Optional.empty() : schema.read(value, join(path, name), problems);
        }

        /** The whole number in field {@code name} when present. */
        public OptionalInt optionalInt(String name, Schema<Double> schema) {
            return optional(name, schema)
                    .map(read -> OptionalInt.of(read.intValue()))
                    .orElse(OptionalInt.empty());
        }

        /** The number in field {@code name} when present. */
        public OptionalDouble optionalNumber(String name, Schema<Double> schema) {
            return optional(name, schema).map(OptionalDouble::of).orElse(OptionalDouble.empty());
        }

        /** The number in field {@code name}, which must be present but may be null. */
        public OptionalDouble requiredNullableNumber(String name, Schema<Double> schema) {
            JsonNode value = present(name);
            if (value == null) {
                problems.add(new ErrorDetail(join(path, name), "Required"));
                return OptionalDouble.empty();
            }
            if (value.isNull()) {
                return OptionalDouble.empty();
            }
            return schema.read(value, join(path, name), problems)
                    .map(OptionalDouble::of)
                    .orElse(OptionalDouble.empty());
        }
    }

    /**
     * Text, with rules on its length (in UTF-16 code units) and content. Every rule it breaks is
     * reported, in the order the rules were added.
     *
     * @param min the fewest characters allowed
     * @param max the most characters allowed
     * @param patterns patterns the whole text must match, each with the problem it reports
     * @param datetime whether the text must be an ISO 8601 date and time with an offset
     */
    record Text(int min, int max, List<Rule> patterns, boolean datetime) implements Schema<String> {

        private static final Pattern DATETIME = Pattern.compile("(?:(?:\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]"
                + "|[02468][048]00|[13579][26]00)-02-29|\\d{4}-(?:(?:0[13578]|1[02])-(?:0[1-9]|[12]\\d|3[01])"
                + "|(?:0[469]|11)-(?:0[1-9]|[12]\\d|30)|(?:02)-(?:0[1-9]|1\\d|2[0-8])))"
                + "T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})");

        /**
         * A pattern and the problem reported when text does not match it.
         *
         * @param pattern matched against the whole text
         * @param message the problem
         */
        public record Rule(Pattern pattern, String message) {}

        public Text {
            patterns = List.copyOf(patterns);
        }

        /** At least {@code value} characters. */
        public Text min(int value) {
            return new Text(value, max, patterns, datetime);
        }

        /** At most {@code value} characters. */
        public Text max(int value) {
            return new Text(min, value, patterns, datetime);
        }

        /** Matching {@code regex} as a whole, or reporting {@code message}. */
        public Text matching(String regex, String message) {
            List<Rule> more = new ArrayList<>(patterns);
            more.add(new Rule(Pattern.compile(regex), message));
            return new Text(min, max, more, datetime);
        }

        /** An ISO 8601 date and time to the second or finer, ending in {@code Z} or an offset. */
        public Text isoDateTime() {
            return new Text(min, max, patterns, true);
        }

        @Override
        public Optional<String> read(JsonNode value, String path, List<ErrorDetail> problems) {
            if (!value.isTextual()) {
                problems.add(new ErrorDetail(path, "Expected string, received " + typeOf(value)));
                return Optional.empty();
            }
            String text = value.asText();
            int before = problems.size();
            if (text.length() < min) {
                problems.add(new ErrorDetail(path, "String must contain at least " + min + " character(s)"));
            }
            if (text.length() > max) {
                problems.add(new ErrorDetail(path, "String must contain at most " + max + " character(s)"));
            }
            for (Rule rule : patterns) {
                if (!rule.pattern().matcher(text).matches()) {
                    problems.add(new ErrorDetail(path, rule.message()));
                }
            }
            if (datetime && !DATETIME.matcher(text).matches()) {
                problems.add(new ErrorDetail(path, "Invalid datetime"));
            }
            return problems.size() > before ? Optional.empty() : Optional.of(text);
        }
    }

    /**
     * A JSON number, with rules on its range and whether it must be whole. Every rule it breaks is
     * reported.
     *
     * @param whole whether it must be a whole number
     * @param min the smallest allowed, inclusive
     * @param max the largest allowed, inclusive
     */
    record Num(boolean whole, double min, double max) implements Schema<Double> {

        /** A whole number. */
        public Num integer() {
            return new Num(true, min, max);
        }

        /** At least {@code value}. */
        public Num min(double value) {
            return new Num(whole, value, max);
        }

        /** At most {@code value}. */
        public Num max(double value) {
            return new Num(whole, min, value);
        }

        @Override
        public Optional<Double> read(JsonNode value, String path, List<ErrorDetail> problems) {
            if (!value.isNumber()) {
                problems.add(new ErrorDetail(path, "Expected number, received " + typeOf(value)));
                return Optional.empty();
            }
            double number = value.doubleValue();
            int before = problems.size();
            if (whole && !Numbers.isInteger(number)) {
                problems.add(new ErrorDetail(path, "Expected integer, received float"));
            }
            if (number < min) {
                problems.add(new ErrorDetail(path, "Number must be greater than or equal to " + Numbers.format(min)));
            }
            if (number > max) {
                problems.add(new ErrorDetail(path, "Number must be less than or equal to " + Numbers.format(max)));
            }
            return problems.size() > before ? Optional.empty() : Optional.of(number);
        }
    }
}
