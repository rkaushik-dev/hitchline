package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.app.IdSource;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Id sources. Each hands {@link Ids#mintId} a block of bytes and lets the domain decide what an id
 * looks like, so the alphabet lives in one place.
 */
public final class IdSources {

    private static final int BYTES = 16;

    private IdSources() {}

    /** Ids from a cryptographically strong random source. */
    public static IdSource randomIds() {
        SecureRandom random = new SecureRandom();
        return kind -> {
            byte[] bytes = new byte[BYTES];
            random.nextBytes(bytes);
            return Ids.mintId(kind, bytes);
        };
    }

    public static IdSource sequentialIds() {
        return sequentialIds(0);
    }

    /**
     * Deterministic ids for tests and fixtures, counting on from {@code seed}. The counter is
     * written across the byte block so consecutive ids differ in more than the last character,
     * which makes a failing assertion easier to read.
     */
    public static IdSource sequentialIds(int seed) {
        AtomicInteger counter = new AtomicInteger(seed);
        return kind -> {
            int value = counter.incrementAndGet();
            byte[] bytes = new byte[BYTES];
            for (int index = 0; index < BYTES; index += 1) {
                bytes[index] = (byte) ((value >> ((index % 4) * 8)) & 0xff);
            }
            return Ids.mintId(kind, bytes);
        };
    }

    /** Hands back ids from a list, then refuses. Used to pin an id in a test. */
    public static IdSource scriptedIds(List<String> values) {
        List<String> held = List.copyOf(values);
        AtomicInteger index = new AtomicInteger();
        return kind -> {
            int at = index.getAndIncrement();
            if (at >= held.size()) {
                index.decrementAndGet();
                throw ValidationException.field("ids", "ran out after " + held.size() + " of them");
            }
            return held.get(at);
        };
    }
}
