package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Reading the bits of a request every route needs. Six routes were each checking for a window and
 * saying something slightly different when it was missing, which is how an api ends up with three
 * spellings of one error. A query parameter given more than once is treated as absent rather than
 * guessed at.
 */
public final class Query {

    private static final String DEFAULT_PURPOSE = "read this";

    private Query() {}

    /** The query parameters given exactly once, by name. */
    public static Map<String, String> singleValued(HttpServletRequest request) {
        Map<String, String> found = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values.length == 1) {
                found.put(name, values[0]);
            }
        });
        return found;
    }

    /** The query parameter {@code name}, when it was given exactly once. */
    public static Optional<String> stringOf(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? Optional.of(values[0]) : Optional.empty();
    }

    /** The path parameter {@code name}, or empty text when the route has none by that name. */
    public static String pathOf(HttpServletRequest request, String name) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map && map.get(name) instanceof String value) {
            return value;
        }
        return "";
    }

    public static DateRange windowOf(HttpServletRequest request) {
        return windowOf(request, DEFAULT_PURPOSE);
    }

    /** The {@code window} the query names, refused with what it was needed for when absent. */
    public static DateRange windowOf(HttpServletRequest request, String what) {
        return DateRange.parse(windowStringOf(request, what));
    }

    /** The {@code window} the query names, when it names one. */
    public static Optional<DateRange> optionalWindowOf(HttpServletRequest request) {
        return stringOf(request, "window").map(DateRange::parse);
    }

    public static String windowStringOf(HttpServletRequest request) {
        return windowStringOf(request, DEFAULT_PURPOSE);
    }

    /** The raw window, for the services that take a string and parse it themselves. */
    public static String windowStringOf(HttpServletRequest request, String what) {
        return stringOf(request, "window")
                .orElseThrow(() -> ValidationException.field("window", "is needed to " + what));
    }

    public static CivilDate dateOf(HttpServletRequest request) {
        return dateOf(request, "on", DEFAULT_PURPOSE);
    }

    /** The day the query parameter {@code name} names. */
    public static CivilDate dateOf(HttpServletRequest request, String name, String what) {
        return CivilDate.parse(rawDateOf(request, name, what));
    }

    public static String rawDateOf(HttpServletRequest request) {
        return rawDateOf(request, "on", DEFAULT_PURPOSE);
    }

    /** The raw text of the day parameter {@code name}. */
    public static String rawDateOf(HttpServletRequest request, String name, String what) {
        return stringOf(request, name).orElseThrow(() -> ValidationException.field(name, "is needed to " + what));
    }

    public static String memberOf(HttpServletRequest request) {
        return memberOf(request, "memberId");
    }

    /** The crew member id in the path parameter {@code name}. */
    public static String memberOf(HttpServletRequest request, String name) {
        return Ids.crewMemberId(pathOf(request, name));
    }

    public static String habitOf(HttpServletRequest request) {
        return habitOf(request, "habitId");
    }

    /** The habit id in the path parameter {@code name}. */
    public static String habitOf(HttpServletRequest request, String name) {
        return Ids.habitId(pathOf(request, name));
    }

    public static String crewOf(HttpServletRequest request) {
        return crewOf(request, "crewId");
    }

    /** The crew id in the path parameter {@code name}. */
    public static String crewOf(HttpServletRequest request, String name) {
        return Ids.crewId(pathOf(request, name));
    }

    public static String queryMemberOf(HttpServletRequest request) {
        return queryMemberOf(request, "list these");
    }

    /** The crew member id in the {@code memberId} query parameter. */
    public static String queryMemberOf(HttpServletRequest request, String what) {
        String raw = stringOf(request, "memberId")
                .orElseThrow(() -> ValidationException.field("memberId", "is needed to " + what));
        return Ids.crewMemberId(raw);
    }
}
