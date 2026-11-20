package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * A stopwatch for the two paths that grow with a crew: expanding a year of obligations and scoring
 * them. There is no benchmark framework here because the answer wanted is "did this get slower",
 * and a median over a few runs answers that well enough to put in a commit message.
 */
public final class Measure {

    private Measure() {}

    /** Milliseconds on the monotonic clock. */
    static double monotonicMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    /** Five timed runs after one warm up, on the monotonic clock. */
    public static Timing measure(String name, Runnable body) {
        return measure(name, body, 5, 1, Measure::monotonicMs);
    }

    /** Times {@code body} over {@code runs} runs after {@code warmup} untimed ones, reading {@code now}. */
    public static Timing measure(String name, Runnable body, int runs, int warmup, DoubleSupplier now) {
        if (runs < 1) {
            throw ValidationException.field("runs", "must be at least one, got " + runs);
        }
        for (int index = 0; index < warmup; index += 1) {
            body.run();
        }
        List<Double> timings = new ArrayList<>();
        for (int index = 0; index < runs; index += 1) {
            double started = now.getAsDouble();
            body.run();
            timings.add(now.getAsDouble() - started);
        }
        List<Double> sorted = new ArrayList<>(timings);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        double median = sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        double total = 0;
        for (double timing : timings) {
            total += timing;
        }
        return new Timing(
                name,
                runs,
                Numbers.roundTo(median, 3),
                Numbers.roundTo(sorted.get(sorted.size() - 1), 3),
                Numbers.roundTo(total, 3));
    }
}
