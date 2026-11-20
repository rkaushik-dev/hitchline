package io.github.rkaushikdev.hitchline.infra.store;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What is in the journal, and what to do about the pile of backups beside it. Both are questions
 * somebody asks once a quarter and neither is worth a dashboard, so they answer in one line each.
 *
 * @param entries how many entries
 * @param byKind how many of each kind, in kind name order
 * @param firstAt the earliest stamp
 * @param lastAt the latest stamp
 * @param writers how many distinct days something was written on
 */
public record StoreStatistics(
        int entries, Map<RecordKind, Integer> byKind, Optional<String> firstAt, Optional<String> lastAt, int writers) {

    public StoreStatistics {
        byKind = Collections.unmodifiableMap(new LinkedHashMap<>(byKind));
    }

    /** Counts the entries by kind and by the day their stamp names. */
    public static StoreStatistics of(List<JournalEntry> entries) {
        Map<RecordKind, Integer> byKind =
                new TreeMap<>((left, right) -> left.wire().compareTo(right.wire()));
        Set<String> days = new HashSet<>();
        for (JournalEntry entry : entries) {
            byKind.merge(entry.kind(), 1, Integer::sum);
            days.add(entry.at().length() > 10 ? entry.at().substring(0, 10) : entry.at());
        }
        List<String> stamps =
                new ArrayList<>(entries.stream().map(JournalEntry::at).toList());
        stamps.sort(null);
        return new StoreStatistics(
                entries.size(),
                byKind,
                stamps.isEmpty() ? Optional.empty() : Optional.of(stamps.get(0)),
                stamps.isEmpty() ? Optional.empty() : Optional.of(stamps.get(stamps.size() - 1)),
                days.size());
    }

    /** One line, such as {@code 3 entries over 2 days: habit 1, member 2}. */
    public String describe() {
        List<String> kinds = new ArrayList<>();
        byKind.forEach((kind, count) -> kinds.add(kind.wire() + " " + count));
        return entries + " entries over " + writers + " days: " + String.join(", ", kinds);
    }

    /**
     * Keeps the newest {@code keep} backups in {@code directory} and removes the rest. Newest is by
     * the name, not by the file time: a restore copies files about and the times move, and the
     * names are stamped when the backup was taken. A dry run removes nothing.
     */
    public static PruneReport pruneBackups(Path directory, int keep, boolean dryRun) {
        if (keep < 1) {
            throw ValidationException.field("keep", "must be at least one, got " + keep);
        }
        List<String> names;
        try (Stream<Path> listed = Files.list(directory)) {
            names = new ArrayList<>(listed.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .toList());
        } catch (IOException failure) {
            throw new UncheckedIOException("could not list the backups in " + directory, failure);
        }
        names.sort(Collections.reverseOrder());
        List<String> kept = names.subList(0, Math.min(keep, names.size()));
        List<String> removed = names.subList(Math.min(keep, names.size()), names.size());
        if (!dryRun) {
            for (String name : removed) {
                try {
                    Files.deleteIfExists(directory.resolve(name));
                } catch (IOException failure) {
                    throw new UncheckedIOException("could not remove the backup " + name, failure);
                }
            }
        }
        return new PruneReport(kept, removed);
    }

    /** The size in bytes of each file, by its name. */
    public static Map<String, Long> backupSizes(List<Path> paths) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (Path path : paths) {
            try {
                sizes.put(path.getFileName().toString(), Files.size(path));
            } catch (IOException failure) {
                throw new UncheckedIOException("could not read the size of " + path, failure);
            }
        }
        return sizes;
    }

    /** Where backups of the store at {@code storePath} go: a {@code backups} directory beside it. */
    public static Path backupDirectoryFor(Path storePath) {
        Path parent = storePath.getParent();
        return parent == null ? Path.of("backups") : parent.resolve("backups");
    }
}
