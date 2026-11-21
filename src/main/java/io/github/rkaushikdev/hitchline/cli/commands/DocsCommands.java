package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.http.Api;
import io.github.rkaushikdev.hitchline.http.ApiDocs;
import io.github.rkaushikdev.hitchline.http.Catalogue;
import io.github.rkaushikdev.hitchline.http.Manifest;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Writing the reference out. It is a command rather than a build step because the same output is
 * what somebody wants on a terminal when they are half way through a curl and cannot remember the
 * parameter name.
 */
public final class DocsCommands {

    private DocsCommands() {}

    /** The {@code docs} commands. */
    public static CommandLine docs(Supplier<Context> context) {
        return Arguments.group(
                "docs",
                "write the api reference out",
                new ApiReference(context),
                new Routes(context),
                new Errors(context),
                new OpenApi(context));
    }

    @Command(name = "api", description = "the whole reference as markdown")
    static final class ApiReference extends ContextCommand {

        @Option(names = "--out", paramLabel = "<file>", description = "write to a file rather than standard output")
        private String out;

        @Option(names = "--base", paramLabel = "<url>", description = "base url to show in the examples")
        private String base;

        ApiReference(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            String body = base == null ? ApiDocs.apiMarkdown(Api.VERSION) : ApiDocs.apiMarkdown(Api.VERSION, base);
            if (out == null) {
                ctx.out(Texts.trimEnd(body));
                return;
            }
            Arguments.writeFile(out, body);
            ctx.out("wrote the reference to " + out);
        }
    }

    @Command(name = "routes", description = "just the list of routes")
    static final class Routes extends ContextCommand {

        Routes(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            ctx.out(ApiDocs.tableOfContents());
        }
    }

    @Command(name = "errors", description = "the error codes and what to do about each")
    static final class Errors extends ContextCommand {

        Errors(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            ctx.out(Texts.trimEnd(Catalogue.catalogueMarkdown()));
        }
    }

    @Command(name = "openapi", description = "the openapi document")
    static final class OpenApi extends ContextCommand {

        @Option(names = "--out", paramLabel = "<file>", description = "write to a file rather than standard output")
        private String out;

        OpenApi(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            String body = Json.pretty(Manifest.openApiDocument(Api.VERSION), 2) + "\n";
            if (out == null) {
                ctx.out(Texts.trimEnd(body));
                return;
            }
            Arguments.writeFile(out, body);
            ctx.out("wrote the openapi document to " + out);
        }
    }
}
