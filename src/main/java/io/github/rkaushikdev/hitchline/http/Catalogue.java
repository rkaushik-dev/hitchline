package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ForbiddenException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.UnsupportedException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What each error code means and what the caller is supposed to do about it. The api answers with
 * a code rather than a sentence, so this is the only place the sentence lives, and the docs and the
 * reference both read it from here rather than repeating it.
 */
public final class Catalogue {

    /**
     * One error code explained.
     *
     * @param code the code a problem body carries
     * @param status the http status it answers with
     * @param title a short name for it
     * @param meaning what went wrong
     * @param remedy what the caller should do next
     * @param retryable whether sending the same request again can work
     */
    public record CatalogueEntry(
            String code, int status, String title, String meaning, String remedy, boolean retryable) {

        public CatalogueEntry {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(meaning, "meaning");
            Objects.requireNonNull(remedy, "remedy");
        }
    }

    /** Every code the api answers with, the internal one last. */
    public static final List<CatalogueEntry> CATALOGUE = List.of(
            new CatalogueEntry(
                    "validation_failed",
                    Problems.statusFor(new ValidationException("x")),
                    "the request did not make sense",
                    "A field was missing, malformed, or outside the range the domain allows.",
                    "Read the details list, fix the named fields and send it again.",
                    false),
            new CatalogueEntry(
                    "not_found",
                    Problems.statusFor(new NotFoundException("habit", "hab_1")),
                    "no such record",
                    "The id in the path or the body names something this store does not hold.",
                    "Check the id. An archived record is gone from every read.",
                    false),
            new CatalogueEntry(
                    "conflict",
                    Problems.statusFor(new ConflictException("x")),
                    "that would contradict something already stored",
                    "Two roster entries covering one day, a member in two crews, or a second writer on the journal.",
                    "Read what is stored, decide which one is right, and send the one that wins.",
                    true),
            new CatalogueEntry(
                    "forbidden",
                    Problems.statusFor(new ForbiddenException("x")),
                    "that token may not do that",
                    "The token is missing, has the wrong scope, or is acting for somebody other than itself.",
                    "Use a token with the scope the endpoint needs, or ask the person to share the habit.",
                    false),
            new CatalogueEntry(
                    "unsupported",
                    Problems.statusFor(new UnsupportedException("x")),
                    "this build cannot do that yet",
                    "A cadence, a backup version or a record shape this build does not understand.",
                    "Upgrade, or ask for the shape a supported build writes.",
                    false),
            new CatalogueEntry(
                    Problems.INTERNAL_ERROR,
                    500,
                    "something went wrong on our side",
                    "An error nobody expected. The detail is in the log against the request id.",
                    "Retry once, then send the request id to whoever runs it.",
                    true));

    private Catalogue() {}

    /** The entry for {@code code}, if the catalogue has one. */
    public static Optional<CatalogueEntry> entryFor(String code) {
        return CATALOGUE.stream().filter(entry -> entry.code().equals(code)).findFirst();
    }

    /** True when a request refused with {@code code} is worth sending again. */
    public static boolean retryable(String code) {
        return entryFor(code).map(CatalogueEntry::retryable).orElse(false);
    }

    /** The codes that answer with {@code status}, in catalogue order. */
    public static List<String> codesFor(int status) {
        return CATALOGUE.stream()
                .filter(entry -> entry.status() == status)
                .map(CatalogueEntry::code)
                .toList();
    }

    /** The catalogue as a markdown table, ending in a newline. */
    public static String catalogueMarkdown() {
        List<String> lines =
                new ArrayList<>(List.of("| code | status | what it means | what to do |", "| --- | --- | --- | --- |"));
        for (CatalogueEntry entry : CATALOGUE) {
            lines.add("| " + entry.code() + " | " + entry.status() + " | " + entry.meaning() + " | " + entry.remedy()
                    + " |");
        }
        return String.join("\n", lines) + "\n";
    }
}
