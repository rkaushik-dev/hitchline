package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.cli.Action;
import io.github.rkaushikdev.hitchline.cli.Context;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A command whose body runs against the context it is handed when it is named, with a domain
 * failure turned into a message and an exit code by {@link Context#runCommand}.
 */
abstract class ContextCommand implements Action {

    private final Supplier<Context> context;

    ContextCommand(Supplier<Context> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public final Integer call() {
        Context ctx = context.get();
        return Context.runCommand(ctx, () -> run(ctx));
    }

    /** What the command does. */
    abstract void run(Context ctx);
}
