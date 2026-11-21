package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Collation;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Counters and buckets, rendered in the text format every scraper understands. There is no client
 * library here on purpose: the whole surface is four numbers a crewing operation actually looks
 * at, and a dependency that pulls in a registry, a default metric set and a http server is not
 * worth it.
 */
public final class Metrics {

    /** Latency bucket bounds, in milliseconds, that suit a small api. */
    public static final List<Double> LATENCY_BOUNDS = List.of(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1_000.0);

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{2,63}");

    /** A number that only goes up, kept per label set. */
    public interface Counter {

        /** Adds {@code amount} to the series with {@code labels}. */
        void add(double amount, Map<String, String> labels);

        /** Adds one to the series with no labels. */
        default void add() {
            add(1, Map.of());
        }

        /** Adds {@code amount} to the series with no labels. */
        default void add(double amount) {
            add(amount, Map.of());
        }

        /** The total of the series with {@code labels}; zero when nothing was added. */
        double read(Map<String, String> labels);

        /** The total of the series with no labels. */
        default double read() {
            return read(Map.of());
        }
    }

    /** Observations counted into buckets, with their count and sum, kept per label set. */
    public interface Histogram {

        void observe(double value, Map<String, String> labels);

        default void observe(double value) {
            observe(value, Map.of());
        }

        double count(Map<String, String> labels);

        default double count() {
            return count(Map.of());
        }

        double sum(Map<String, String> labels);

        default double sum() {
            return sum(Map.of());
        }
    }

    private enum Kind {
        COUNTER("counter"),
        HISTOGRAM("histogram");

        private final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    private record Series(
            String name,
            String help,
            Kind kind,
            List<Double> bounds,
            Map<String, Double> counts,
            Map<String, Double> sums,
            Map<String, long[]> buckets) {}

    private final Map<String, Series> series = new LinkedHashMap<>();

    private Metrics() {}

    /** An empty registry. */
    public static Metrics create() {
        return new Metrics();
    }

    private static String labelKey(Map<String, String> labels) {
        return labels.keySet().stream()
                .sorted()
                .map(name -> name + "=\"" + escapeLabel(labels.get(name)) + "\"")
                .collect(Collectors.joining(","));
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private synchronized Series define(String name, String help, Kind kind, List<Double> bounds) {
        if (!NAME.matcher(name).matches()) {
            throw ValidationException.field("name", "is not a usable metric name: " + name);
        }
        Series existing = series.get(name);
        if (existing != null) {
            if (existing.kind() != kind) {
                throw ValidationException.field("name", name + " is already a " + existing.kind().word);
            }
            return existing;
        }
        Series created = new Series(
                name,
                help,
                kind,
                bounds.stream().sorted().toList(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>());
        series.put(name, created);
        return created;
    }

    /** The counter called {@code name}, made on first use. */
    public Counter counter(String name, String help) {
        Series entry = define(name, help, Kind.COUNTER, List.of());
        return new Counter() {
            @Override
            public void add(double amount, Map<String, String> labels) {
                synchronized (Metrics.this) {
                    entry.counts().merge(labelKey(labels), amount, Double::sum);
                }
            }

            @Override
            public double read(Map<String, String> labels) {
                synchronized (Metrics.this) {
                    return entry.counts().getOrDefault(labelKey(labels), 0.0);
                }
            }
        };
    }

    /** The histogram called {@code name} over {@code bounds}, made on first use. */
    public Histogram histogram(String name, String help, List<Double> bounds) {
        Series entry = define(name, help, Kind.HISTOGRAM, bounds);
        return new Histogram() {
            @Override
            public void observe(double value, Map<String, String> labels) {
                synchronized (Metrics.this) {
                    String key = labelKey(labels);
                    long[] counts = entry.buckets()
                            .computeIfAbsent(
                                    key, missing -> new long[entry.bounds().size() + 1]);
                    int index = 0;
                    while (index < entry.bounds().size()
                            && !(value <= entry.bounds().get(index))) {
                        index += 1;
                    }
                    counts[index] += 1;
                    entry.counts().merge(key, 1.0, Double::sum);
                    entry.sums().merge(key, value, Double::sum);
                }
            }

            @Override
            public double count(Map<String, String> labels) {
                synchronized (Metrics.this) {
                    return entry.counts().getOrDefault(labelKey(labels), 0.0);
                }
            }

            @Override
            public double sum(Map<String, String> labels) {
                synchronized (Metrics.this) {
                    return entry.sums().getOrDefault(labelKey(labels), 0.0);
                }
            }
        };
    }

    /** Every series in the text exposition format, metrics sorted by name; empty when none exist. */
    public synchronized String render() {
        List<String> lines = new ArrayList<>();
        List<Series> sorted = series.values().stream()
                .sorted(Comparator.comparing(Series::name, Collation.ORDER))
                .toList();
        for (Series entry : sorted) {
            lines.add("# HELP " + entry.name() + " " + entry.help());
            lines.add("# TYPE " + entry.name() + " " + entry.kind().word);
            if (entry.kind() == Kind.COUNTER) {
                entry.counts()
                        .forEach((key, value) -> lines.add(
                                entry.name() + (key.isEmpty() ? "" : "{" + key + "}") + " " + Numbers.format(value)));
                continue;
            }
            entry.buckets().forEach((key, counts) -> {
                long running = 0;
                for (int index = 0; index < entry.bounds().size(); index += 1) {
                    running += counts[index];
                    String bound = "le=\"" + Numbers.format(entry.bounds().get(index)) + "\"";
                    lines.add(entry.name() + "_bucket{" + joined(key, bound) + "} " + running);
                }
                running += counts[entry.bounds().size()];
                lines.add(entry.name() + "_bucket{" + joined(key, "le=\"+Inf\"") + "} " + running);
                String suffix = key.isEmpty() ? "" : "{" + key + "}";
                lines.add(entry.name() + "_sum" + suffix + " "
                        + Numbers.format(entry.sums().getOrDefault(key, 0.0)));
                lines.add(entry.name() + "_count" + suffix + " "
                        + Numbers.format(entry.counts().getOrDefault(key, 0.0)));
            });
        }
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    private static String joined(String key, String bound) {
        return Stream.of(key, bound).filter(part -> !part.isEmpty()).collect(Collectors.joining(","));
    }
}
