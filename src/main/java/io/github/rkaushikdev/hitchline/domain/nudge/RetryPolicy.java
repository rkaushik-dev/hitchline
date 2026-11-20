package io.github.rkaushikdev.hitchline.domain.nudge;

/**
 * When a sent nudge nobody has looked at is sent again.
 *
 * @param maxAttempts how many sends in all
 * @param backoffMinutes how long to wait after a send before the next
 */
public record RetryPolicy(int maxAttempts, int backoffMinutes) {

    /** Three attempts, half an hour apart. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(3, 30);
}
