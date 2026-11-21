package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;

/** Reading option values the commands share, and writing a command's output to a file. */
final class Arguments {

    private Arguments() {}

    /**
     * A group of commands under {@code name}, with a {@code help} command of its own. Naming the
     * group without one of its commands is a usage error.
     */
    static CommandLine group(String name, String description, Object... commands) {
        CommandSpec spec = CommandSpec.create().name(name);
        spec.usageMessage().description(description);
        CommandLine group = new CommandLine(spec);
        for (Object command : commands) {
            group.addSubcommand(command);
        }
        group.addSubcommand(new HelpCommand());
        return group;
    }

    /**
     * The whole number {@code text} reads as, by {@link Numbers#parse}. Anything else, including a
     * number too large for a count, is refused under {@code field} with {@code problem}.
     */
    static int wholeNumber(String text, String field, String problem) {
        double value = Numbers.parse(text);
        if (!Numbers.isInteger(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw ValidationException.field(field, problem);
        }
        return (int) value;
    }

    /** The value of {@code type} written {@code text}, refused under {@code field} when there is none. */
    static <E extends Enum<E> & WireValue> E word(Class<E> type, String field, String text) {
        return WireValue.fromWire(type, text)
                .orElseThrow(() -> ValidationException.field(field, "must be one of " + WireValue.join(type, ", ")));
    }

    /** Writes {@code body} to {@code file} as UTF-8, replacing what was there. */
    static void writeFile(String file, String body) {
        try {
            Files.writeString(Path.of(file), body, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not write " + file, failure);
        }
    }
}
