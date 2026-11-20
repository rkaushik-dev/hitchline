package io.github.rkaushikdev.hitchline.domain.checkin;

import java.util.Optional;

/**
 * What a verification of the chain found.
 *
 * @param ok true when every digest matched
 * @param checked how many entries were compared before it stopped
 * @param brokenAt the first entry whose digest did not match, when one did not
 */
public record ChainCheck(boolean ok, int checked, Optional<String> brokenAt) {

    /** One line, such as {@code 3 entries verified} or {@code broke at chk_... after 2 entries}. */
    public String describe() {
        if (ok) {
            return checked + " entries verified";
        }
        return "broke at " + brokenAt.orElse("the end") + " after " + checked + " entries";
    }
}
