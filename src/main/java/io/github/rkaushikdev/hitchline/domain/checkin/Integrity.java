package io.github.rkaushikdev.hitchline.domain.checkin;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A hash chain over the ledger. An operator being audited has to be able to say that nothing was
 * quietly edited after the fact, and a chain gives that without a database or a signature: change
 * one row and every digest after it stops matching. Each digest is the SHA-256, in lower case hex,
 * of the UTF-8 text: the previous digest, U+001E, then the entry's fields joined with U+001F.
 */
public final class Integrity {

    /** The digest the first entry is folded onto: sixty four zeros. */
    public static final String GENESIS = "0".repeat(64);

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final String RECORD_SEPARATOR = "\u001e";
    private static final String UNIT_SEPARATOR = "\u001f";

    private Integrity() {}

    private static String fieldsOf(CheckIn entry) {
        return String.join(
                UNIT_SEPARATOR,
                entry.id(),
                entry.habitId(),
                entry.memberId(),
                Zones.formatInstant(entry.at()),
                Zones.formatInstant(entry.recordedAt()),
                entry.reading().isPresent() ? Numbers.format(entry.reading().getAsDouble()) : "",
                entry.note(),
                entry.source().wire(),
                entry.idempotencyKey().orElse(""),
                entry.voidedAt().map(Zones::formatInstant).orElse(""));
    }

    /** The digest of {@code entry} folded onto {@code previous}. */
    public static String digestOf(CheckIn entry, String previous) {
        if (!DIGEST.matcher(previous).matches()) {
            throw ValidationException.field("previous", "must be a sha256 digest in lower case hex");
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest =
                    sha.digest((previous + RECORD_SEPARATOR + fieldsOf(entry)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every Java platform provides SHA-256", missing);
        }
    }

    /** Digests every entry in event order, each folded onto the one before it. */
    public static List<ChainLink> chainOf(List<CheckIn> entries) {
        List<CheckIn> ordered = new ArrayList<>(entries);
        ordered.sort(CheckIn.ORDER);
        List<ChainLink> links = new ArrayList<>();
        String previous = GENESIS;
        for (CheckIn entry : ordered) {
            previous = digestOf(entry, previous);
            links.add(new ChainLink(entry, previous));
        }
        return links;
    }

    /** The digest of the last entry, or the genesis digest for an empty ledger. */
    public static String headOf(List<CheckIn> entries) {
        List<ChainLink> links = chainOf(entries);
        return links.isEmpty() ? GENESIS : links.get(links.size() - 1).digest();
    }

    /**
     * Recomputes the chain and compares it with digests recorded earlier. The first mismatch is the
     * interesting one: everything after it is a consequence.
     */
    public static ChainCheck verifyChain(List<CheckIn> entries, List<String> recorded) {
        List<ChainLink> links = chainOf(entries);
        if (recorded.size() != links.size()) {
            Optional<String> brokenAt = recorded.size() < links.size()
                    ? Optional.of(links.get(recorded.size()).entry().id())
                    : Optional.empty();
            return new ChainCheck(false, links.size(), brokenAt);
        }
        for (int index = 0; index < links.size(); index += 1) {
            ChainLink link = links.get(index);
            if (!recorded.get(index).equals(link.digest())) {
                return new ChainCheck(false, index + 1, Optional.of(link.entry().id()));
            }
        }
        return new ChainCheck(true, links.size(), Optional.empty());
    }
}
