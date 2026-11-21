package io.github.rkaushikdev.hitchline.cli;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.ErrorCode;
import io.github.rkaushikdev.hitchline.domain.ErrorDetail;

/**
 * What a command is handed. Output goes through the context rather than straight to the console,
 * so a test can read back what a command printed and the same command body works under a pipe.
 */
public interface Context {

    /** The command did what it was asked. */
    int EXIT_OK = 0;

    /** The command was understood and could not be carried out. */
    int EXIT_FAILED = 1;

    /** The command line or a value on it was wrong. */
    int EXIT_MISUSED = 2;

    /** The store the command reads and writes. */
    Ports ports();

    /** The settings in force. */
    Config config();

    /** Prints one line of output. */
    void out(String line);

    /** Prints one line of failure. */
    void fail(String line);

    /** A context that prints to standard output and standard error. */
    static Context consoleContext(Ports ports, Config config) {
        return new ConsoleContext(ports, config);
    }

    /** A context that keeps what was printed, which is how the command line is tested. */
    static Recorder recordingContext(Ports ports, Config config) {
        return new Recorder(ports, config);
    }

    /**
     * Runs a command body and turns a domain failure into a message and an exit code: the code and
     * message on one line, then one indented line per detail, a detail with no path written against
     * a dash. A validation failure exits {@link #EXIT_MISUSED}, any other domain failure
     * {@link #EXIT_FAILED}. Anything that is not a domain failure is a bug, so it is rethrown with
     * its stack rather than dressed up as a usage error.
     */
    static int runCommand(Context context, Runnable body) {
        try {
            body.run();
            return EXIT_OK;
        } catch (DomainException failure) {
            context.fail(failure.code().wire() + ": " + failure.getMessage());
            for (ErrorDetail detail : failure.details()) {
                context.fail("  " + (detail.path().isEmpty() ? "-" : detail.path()) + " " + detail.message());
            }
            return failure.code() == ErrorCode.VALIDATION_FAILED ? EXIT_MISUSED : EXIT_FAILED;
        }
    }
}
