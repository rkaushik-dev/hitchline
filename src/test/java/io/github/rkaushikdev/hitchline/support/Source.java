package io.github.rkaushikdev.hitchline.support;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A tiny deterministic generator for the invariant tests. Bringing a property testing library in
 * would mean a shrinker and a runner the project does not otherwise need; a seeded sequence and a
 * loop find the same class of bug, and the failing case is reported with its seed so it can be
 * replayed.
 *
 * <p>The sequence is an xorshift over 32 bits, so the same seed always gives the same readings.
 */
public final class Source {

    private int state;

    private Source(int seed) {
        state = seed == 0 ? 1 : seed;
    }

    /** A generator starting from {@code seed}; a seed of zero starts from one instead. */
    public static Source of(int seed) {
        return new Source(seed);
    }

    /** The next reading, between zero and one, both ends included. */
    public double next() {
        int value = state;
        value ^= value << 13;
        value ^= value >>> 17;
        value ^= value << 5;
        state = value;
        return Integer.toUnsignedLong(value) / 4_294_967_295.0;
    }

    /** A whole number from {@code lowest} to {@code highest}, both ends included. */
    public int between(int lowest, int highest) {
        return lowest + (int) Math.floor(next() * (highest - lowest + 1));
    }

    /** One of {@code items}, chosen by the next reading. */
    public <T> T pick(List<T> items) {
        int index = (int) Math.floor(next() * items.size());
        if (index >= items.size()) {
            throw new IllegalArgumentException("cannot pick from an empty list");
        }
        return items.get(index);
    }

    /** True with roughly the probability given. */
    public boolean chance(double probability) {
        return next() < probability;
    }

    /**
     * Runs {@code body} once for each seed from 1 to {@code count}, handing it a generator seeded
     * with {@code seed * 7919}. A failure is rethrown with the seed that produced it at the front
     * of its message.
     */
    public static void forEachSeed(int count, BiConsumer<Source, Integer> body) {
        for (int seed = 1; seed <= count; seed += 1) {
            try {
                body.accept(of(seed * 7919), seed);
            } catch (AssertionError | RuntimeException error) {
                String message = Objects.requireNonNullElse(error.getMessage(), "");
                throw new AssertionError("seed " + seed + ": " + message, error);
            }
        }
    }
}
