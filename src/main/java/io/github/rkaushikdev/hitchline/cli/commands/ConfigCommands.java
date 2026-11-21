package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.http.Api;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * What this process thinks it is running as. Half the support questions about a tool like this are
 * answered by printing the settings it actually read, rather than the ones somebody meant to set.
 */
public final class ConfigCommands {

    private ConfigCommands() {}

    /** The {@code config} commands. */
    public static CommandLine config(Supplier<Context> context) {
        return Arguments.group(
                "config",
                "read back the settings in force",
                new Show(context),
                new Tokens(context),
                new Version(context));
    }

    @Command(name = "show", description = "print every setting, with the tokens redacted")
    static final class Show extends ContextCommand {

        Show(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            Config config = ctx.config();
            ctx.out(Render.keyValues(List.of(
                    Map.entry("api version", Api.VERSION),
                    Map.entry("store", config.store().wire()),
                    Map.entry("store path", config.store() == StoreKind.FILE ? config.storePath() : "not used"),
                    Map.entry("host", config.host()),
                    Map.entry("port", Integer.toString(config.port())),
                    Map.entry("log level", config.logLevel().wire()),
                    Map.entry("default zone", config.defaultZone()),
                    Map.entry("writes a minute", Integer.toString(config.writesPerMinute())),
                    Map.entry("burst", Integer.toString(config.burst())),
                    Map.entry("tokens", config.tokens().size() + " loaded"),
                    Map.entry("summary", config.describe()))));
        }
    }

    @Command(name = "tokens", description = "list the tokens by member and scope, never the token itself")
    static final class Tokens extends ContextCommand {

        private static final int SHOWN = 4;

        Tokens(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<ApiToken> tokens = ctx.config().tokens();
            if (tokens.isEmpty()) {
                ctx.out("no tokens are loaded, so every request will be turned away");
                return;
            }
            for (ApiToken token : tokens) {
                String secret = token.token();
                ctx.out(token.memberId() + "  " + String.join("|", token.scopes()) + "  "
                        + secret.substring(0, Math.min(SHOWN, secret.length())) + "...");
            }
        }
    }

    @Command(name = "version", description = "the api version, the build and what it is running on")
    static final class Version extends ContextCommand {

        Version(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            ctx.out(Render.keyValues(List.of(
                    Map.entry("api", Api.VERSION),
                    Map.entry("java", Runtime.version().toString()),
                    Map.entry("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch")),
                    Map.entry("store", ctx.config().store().wire()))));
        }
    }
}
