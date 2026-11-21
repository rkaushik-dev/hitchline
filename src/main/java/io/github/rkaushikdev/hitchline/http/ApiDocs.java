package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.http.Manifest.RouteDoc;
import io.github.rkaushikdev.hitchline.http.Manifest.RouteParameter;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The api reference, written from the same manifest the server registers and the same catalogue
 * it answers with. Documentation that is generated from the thing it describes cannot drift from
 * it, which is the only kind that stays true a year later.
 */
public final class ApiDocs {

    /** The base url shown when none is given: where a default install listens. */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8417";

    private ApiDocs() {}

    private static String scopeNote(RouteDoc route) {
        return switch (route.scope()) {
            case NONE -> "no token needed";
            case READ -> "needs a token with the read scope";
            case WRITE -> "needs the write scope";
            case ADMIN -> "needs the admin scope";
        };
    }

    private static List<String> parameterTable(RouteDoc route) {
        if (route.parameters().isEmpty()) {
            return List.of();
        }
        List<String> lines =
                new ArrayList<>(List.of("", "| parameter | in | required | what it is |", "| --- | --- | --- | --- |"));
        for (RouteParameter parameter : route.parameters()) {
            lines.add("| " + parameter.name() + " | " + parameter.in().wire() + " | "
                    + (parameter.required() ? "yes" : "no") + " | " + parameter.description() + " |");
        }
        return lines;
    }

    private static String routeMarkdown(RouteDoc route) {
        List<String> lines = new ArrayList<>(
                List.of("### " + route.method() + " " + route.path(), "", route.summary(), "", scopeNote(route)));
        lines.addAll(parameterTable(route));
        return String.join("\n", lines);
    }

    /** The whole reference for api {@code version}, showing the default base url. */
    public static String apiMarkdown(String version) {
        return apiMarkdown(version, DEFAULT_BASE_URL);
    }

    /** The whole reference for api {@code version}, ending in exactly one newline. */
    public static String apiMarkdown(String version, String baseUrl) {
        List<String> lines = new ArrayList<>(List.of(
                "# hitchline api " + version,
                "",
                "Every response is json. Errors carry a code, a message and a list of the fields",
                "that were wrong, and the code is the part worth branching on.",
                "",
                "Base url in a default install: " + baseUrl,
                "",
                "## Routes",
                ""));

        for (RouteDoc route : Manifest.ROUTES) {
            lines.add(routeMarkdown(route));
            lines.add("");
        }

        lines.add("## Errors");
        lines.add("");
        lines.add(Texts.trimEnd(Catalogue.catalogueMarkdown()));
        lines.add("");
        lines.add("There are " + Catalogue.CATALOGUE.size() + " codes in all. Anything not on that list is a bug.");
        lines.add("");

        return Texts.trimEnd(String.join("\n", lines)) + "\n";
    }

    /** One line per route, {@code - METHOD path}. */
    public static String tableOfContents() {
        return Manifest.ROUTES.stream()
                .map(route -> "- " + route.method() + " " + route.path())
                .collect(Collectors.joining("\n"));
    }
}
