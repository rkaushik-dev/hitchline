package io.github.rkaushikdev.hitchline.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the api serves, written down. It is kept by hand rather than generated from the router,
 * because the useful half of an api description is the prose about what a window means and no
 * generator writes that.
 */
public final class Manifest {

    /** An http method a route answers. */
    public enum Method {
        GET,
        POST,
        PUT,
        PATCH,
        DELETE
    }

    /** Where a parameter is read from. */
    public enum Location {
        PATH("path"),
        QUERY("query");

        private final String wire;

        Location(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    /** Who may call a route: anybody, or a token carrying one of the three scopes. */
    public enum RouteScope {
        NONE("none"),
        READ("read"),
        WRITE("write"),
        ADMIN("admin");

        private final String wire;

        RouteScope(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    /**
     * One parameter of a route.
     *
     * @param name the parameter's name
     * @param in where it is read from
     * @param required whether the route refuses a request without it
     * @param description what it is, in a sentence fragment
     */
    public record RouteParameter(String name, Location in, boolean required, String description) {

        public RouteParameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(in, "in");
            Objects.requireNonNull(description, "description");
        }

        /** The same parameter with {@code value} as its requirement. */
        public RouteParameter withRequired(boolean value) {
            return new RouteParameter(name, in, value, description);
        }
    }

    /**
     * One route the api promises.
     *
     * @param method the http method
     * @param path the path, with parameters written {@code :name}
     * @param summary what the route does, in a sentence fragment
     * @param scope who may call it
     * @param parameters the parameters it reads, in the order a reader meets them
     */
    public record RouteDoc(
            Method method, String path, String summary, RouteScope scope, List<RouteParameter> parameters) {

        public RouteDoc {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(scope, "scope");
            parameters = List.copyOf(parameters);
        }
    }

    private static final RouteParameter WINDOW =
            new RouteParameter("window", Location.QUERY, true, "inclusive day range written as YYYY-MM-DD..YYYY-MM-DD");

    private static final RouteParameter MEMBER =
            new RouteParameter("memberId", Location.PATH, true, "the crew member the record belongs to");

    /** Every documented route, in the order somebody reading the api meets them. */
    public static final List<RouteDoc> ROUTES = List.of(
            new RouteDoc(Method.GET, "/health", "liveness and the clock reading", RouteScope.NONE, List.of()),
            new RouteDoc(Method.GET, "/metrics", "counters in the text exposition format", RouteScope.NONE, List.of()),
            new RouteDoc(Method.POST, "/habits", "give somebody a habit", RouteScope.WRITE, List.of()),
            new RouteDoc(
                    Method.GET,
                    "/habits",
                    "list the habits of one member",
                    RouteScope.READ,
                    List.of(
                            new RouteParameter("memberId", Location.QUERY, true, "whose habits to list"),
                            new RouteParameter("limit", Location.QUERY, false, "rows per page, up to 500"),
                            new RouteParameter("after", Location.QUERY, false, "cursor from a previous page"))),
            new RouteDoc(Method.GET, "/habits/:habitId", "read one habit", RouteScope.READ, List.of()),
            new RouteDoc(Method.PATCH, "/habits/:habitId", "change a habit", RouteScope.WRITE, List.of()),
            new RouteDoc(Method.POST, "/check-ins", "record that somebody did the thing", RouteScope.WRITE, List.of()),
            new RouteDoc(
                    Method.GET,
                    "/members/:memberId/check-ins",
                    "list check ins with the rotation day each landed on",
                    RouteScope.READ,
                    List.of(MEMBER, WINDOW.withRequired(false))),
            new RouteDoc(
                    Method.POST,
                    "/check-ins/:checkInId/void",
                    "take a check in out of the count",
                    RouteScope.WRITE,
                    List.of()),
            new RouteDoc(Method.PATCH, "/check-ins/:checkInId", "amend a reading", RouteScope.WRITE, List.of()),
            new RouteDoc(
                    Method.GET,
                    "/members/:memberId/roster",
                    "the role and the clock for each day",
                    RouteScope.READ,
                    List.of(MEMBER, WINDOW)),
            new RouteDoc(
                    Method.GET,
                    "/members/:memberId/adherence",
                    "how a window went, by habit and by hitch",
                    RouteScope.READ,
                    List.of(MEMBER, WINDOW)),
            new RouteDoc(
                    Method.GET,
                    "/members/:memberId/nudges",
                    "what the planner would send",
                    RouteScope.READ,
                    List.of(MEMBER, WINDOW)),
            new RouteDoc(
                    Method.POST,
                    "/members/:memberId/sync",
                    "take a batch a handset queued while it had no signal",
                    RouteScope.WRITE,
                    List.of(MEMBER)),
            new RouteDoc(
                    Method.GET,
                    "/audit",
                    "what anybody did, newest first",
                    RouteScope.ADMIN,
                    List.of(
                            WINDOW.withRequired(false),
                            new RouteParameter("memberId", Location.QUERY, false, "narrow to one person"),
                            new RouteParameter("zone", Location.QUERY, false, "zone the window is read in"))),
            new RouteDoc(Method.POST, "/crews", "start a crew", RouteScope.ADMIN, List.of()),
            new RouteDoc(
                    Method.GET,
                    "/crews/:crewId/comparison",
                    "the crew side by side, minus anything not shared",
                    RouteScope.READ,
                    List.of(
                            new RouteParameter("crewId", Location.PATH, true, "the crew to read"),
                            WINDOW,
                            new RouteParameter("on", Location.QUERY, true, "the day to read membership as"))));

    private static final Pattern PATH_PARAMETER = Pattern.compile(":([A-Za-z]+)");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private Manifest() {}

    /** The routes open to {@code scope}, in manifest order. */
    public static List<RouteDoc> routesByScope(RouteScope scope) {
        return ROUTES.stream().filter(route -> route.scope() == scope).toList();
    }

    /** The route answering {@code method} on {@code path}, if the manifest holds one. */
    public static Optional<RouteDoc> findRoute(Method method, String path) {
        return ROUTES.stream()
                .filter(route -> route.method() == method && route.path().equals(path))
                .findFirst();
    }

    /** The names of the parameters written into {@code path}, in order. */
    public static List<String> pathParameters(String path) {
        Matcher matcher = PATH_PARAMETER.matcher(path);
        return matcher.results().map(found -> found.group(1)).toList();
    }

    private static String openApiPath(String path) {
        return PATH_PARAMETER.matcher(path).replaceAll("{$1}");
    }

    /** Renders the manifest as an openapi document titled {@code hitchline}. */
    public static ObjectNode openApiDocument(String version) {
        return openApiDocument(version, "hitchline");
    }

    /** Renders the manifest as an openapi document, which is what tooling wants. */
    public static ObjectNode openApiDocument(String version, String title) {
        if (!VERSION.matcher(version).matches()) {
            throw ValidationException.field("version", "must look like 1.2.3, got " + version);
        }
        ObjectNode paths = Json.object();
        for (RouteDoc route : ROUTES) {
            String key = openApiPath(route.path());
            ObjectNode entry = paths.has(key) ? (ObjectNode) paths.get(key) : paths.putObject(key);
            ObjectNode operation = entry.putObject(route.method().name().toLowerCase(Locale.ROOT));
            operation.put("summary", route.summary());
            ArrayNode security = operation.putArray("security");
            if (route.scope() != RouteScope.NONE) {
                security.addObject().putArray("bearerAuth");
            }
            ArrayNode parameters = operation.putArray("parameters");
            for (RouteParameter parameter : route.parameters()) {
                ObjectNode written = parameters.addObject();
                written.put("name", parameter.name());
                written.put("in", parameter.in().wire());
                written.put("required", parameter.required());
                written.put("description", parameter.description());
                written.putObject("schema").put("type", "string");
            }
            ObjectNode responses = operation.putObject("responses");
            responses.putObject("200").put("description", "the record or records asked for");
            responses.putObject("400").put("description", "the request did not make sense");
            responses.putObject("403").put("description", "the token may not do that");
        }

        ObjectNode document = Json.object();
        document.put("openapi", "3.1.0");
        ObjectNode info = document.putObject("info");
        info.put("title", title);
        info.put("version", version);
        document.putObject("components")
                .putObject("securitySchemes")
                .putObject("bearerAuth")
                .put("type", "http")
                .put("scheme", "bearer");
        document.set("paths", paths);
        return document;
    }
}
