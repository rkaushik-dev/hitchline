package io.github.rkaushikdev.hitchline.cli;

import java.util.concurrent.Callable;

/**
 * A command that does something when it is named on the command line, as opposed to a group that
 * only holds other commands. It answers its exit code, and anything it throws is a bug that
 * reaches the caller unchanged.
 */
public interface Action extends Callable<Integer> {

    @Override
    Integer call();
}
