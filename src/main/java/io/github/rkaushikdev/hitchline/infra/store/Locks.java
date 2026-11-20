package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.MalformedJsonException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * An advisory lock beside the journal. Two writers on one file is the failure this code base is
 * least able to detect after the fact: both would append and the later reader would see a
 * plausible, wrong history. Exclusive create is the one file system primitive that behaves the
 * same on every platform this runs on.
 */
public final class Locks {

    /** A lock older than this is taken over. */
    public static final long DEFAULT_STALE_MS = 15 * 60_000L;

    private Locks() {}

    /** The lock file for {@code target}: the same path with {@code .lock} on the end. */
    public static Path lockPathFor(Path target) {
        if (Texts.trim(target.toString()).isEmpty()) {
            throw ValidationException.field("path", "must not be empty");
        }
        return target.resolveSibling(target.getFileName() + ".lock");
    }

    private static Optional<LockOwner> readOwner(String text) {
        try {
            JsonNode parsed = Json.parse(text);
            JsonNode pid = parsed.get("pid");
            JsonNode host = parsed.get("host");
            JsonNode at = parsed.get("at");
            if (!parsed.isObject() || pid == null || !pid.isNumber() || host == null || !host.isTextual()) {
                return Optional.empty();
            }
            if (at == null || !at.isTextual()) {
                return Optional.empty();
            }
            return Optional.of(new LockOwner(pid.longValue(), host.textValue(), at.textValue()));
        } catch (MalformedJsonException unreadable) {
            return Optional.empty();
        }
    }

    /** Who holds the lock on {@code target}, when the lock file is there and readable. */
    public static Optional<LockOwner> readLock(Path target) {
        try {
            return readOwner(Files.readString(lockPathFor(target), StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read the lock for " + target, failure);
        }
    }

    public static LockHolder takeLock(Path target, LockOwner owner) {
        return takeLock(target, owner, DEFAULT_STALE_MS, System.currentTimeMillis());
    }

    /**
     * Takes the lock, or says who holds it. A lock whose stamp is older than the stale window, or
     * that nobody can read, is taken over, because a process killed with the lock held would
     * otherwise shut the store for good.
     */
    public static LockHolder takeLock(Path target, LockOwner owner, long staleAfterMs, long nowMs) {
        Path path = lockPathFor(target);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try {
                write(path, owner);
            } catch (FileAlreadyExistsException taken) {
                Optional<LockOwner> held = readLock(target);
                double age = held.map(holder -> ageOf(holder, nowMs)).orElse(Double.POSITIVE_INFINITY);
                if (held.isPresent() && age < staleAfterMs) {
                    LockOwner holder = held.get();
                    throw new ConflictException(path + " is held by pid " + holder.pid() + " on " + holder.host()
                            + " since " + holder.at());
                }
                Files.deleteIfExists(path);
                write(path, owner);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("could not take the lock for " + target, failure);
        }
        return new LockHolder(path, owner);
    }

    private static double ageOf(LockOwner holder, long nowMs) {
        try {
            return nowMs - Zones.parseInstant(holder.at()).toEpochMilli();
        } catch (ValidationException unreadableStamp) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static void write(Path path, LockOwner owner) throws IOException {
        String text = Json.stringify(
                Json.object().put("pid", owner.pid()).put("host", owner.host()).put("at", owner.at()));
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
