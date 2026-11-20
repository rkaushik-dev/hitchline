package io.github.rkaushikdev.hitchline.domain;

import java.util.regex.Pattern;

/**
 * Record ids: a three letter prefix naming the kind, an underscore and sixteen characters of
 * Crockford base32. The alphabet leaves out I, L, O and U so an id survives being read aloud.
 * Ids are plain strings once they have been checked here.
 */
public final class Ids {

    /** Crockford base32 without I, L, O and U. */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    static final int SUFFIX_LENGTH = 16;
    private static final Pattern SUFFIX = Pattern.compile("[" + ALPHABET + "]{" + SUFFIX_LENGTH + "}");

    private Ids() {}

    public static String idPrefix(IdKind kind) {
        return kind.prefix();
    }

    /** Checks {@code raw} is an id of {@code kind} and hands it back. */
    public static String parse(IdKind kind, String raw) {
        String prefix = kind.prefix();
        int separator = raw.indexOf('_');
        if (separator < 0) {
            throw ValidationException.field(kind.label(), "must look like " + prefix + "_" + "X".repeat(SUFFIX_LENGTH));
        }
        String head = raw.substring(0, separator);
        String tail = raw.substring(separator + 1);
        if (!head.equals(prefix)) {
            throw ValidationException.field(
                    kind.label(), "must start with " + prefix + "_ but starts with " + head + "_");
        }
        if (!SUFFIX.matcher(tail).matches()) {
            throw ValidationException.field(kind.label(), "has an unreadable suffix " + tail);
        }
        return raw;
    }

    public static String crewMemberId(String raw) {
        return parse(IdKind.CREW_MEMBER, raw);
    }

    public static String crewId(String raw) {
        return parse(IdKind.CREW, raw);
    }

    public static String habitId(String raw) {
        return parse(IdKind.HABIT, raw);
    }

    public static String checkInId(String raw) {
        return parse(IdKind.CHECK_IN, raw);
    }

    /** Answers whether {@code raw} is an id of {@code kind}, without throwing. */
    public static boolean isWellFormed(IdKind kind, String raw) {
        try {
            parse(kind, raw);
            return true;
        } catch (ValidationException refused) {
            return false;
        }
    }

    /**
     * Builds an id from bytes supplied by the caller. The domain never reaches for a random
     * source of its own, so tests can hand it a fixed sequence. Each of the first sixteen bytes,
     * read as unsigned, picks a character modulo the alphabet's length.
     */
    public static String mintId(IdKind kind, byte[] bytes) {
        if (bytes.length < SUFFIX_LENGTH) {
            throw ValidationException.field("bytes", "needs at least " + SUFFIX_LENGTH + " entries");
        }
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int index = 0; index < SUFFIX_LENGTH; index += 1) {
            suffix.append(ALPHABET.charAt(Byte.toUnsignedInt(bytes[index]) % ALPHABET.length()));
        }
        return kind.prefix() + "_" + suffix;
    }
}
