package io.github.rkaushikdev.hitchline.http.middleware;

import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.ApiJson;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Problems.ProblemBody;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.springframework.http.MediaType;

/**
 * A token bucket per caller. The api sits behind a proxy in any real deployment, but a handset with
 * a retry loop can still hammer one process, and a nudge that fires on every crew member at 06:00 is
 * a burst by design.
 */
public final class RateLimit {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private RateLimit() {}

    /** A caller who has used up their bucket. */
    public static final class TooManyRequestsException extends ConflictException {

        private final long retryAfterSeconds;

        public TooManyRequestsException(long retryAfterSeconds) {
            super("too many requests, try again in " + retryAfterSeconds + " seconds");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        /** How long to wait before the bucket holds a whole token again. */
        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** The buckets, one per caller, each refilling continuously up to its capacity. */
    public static final class Limiter {

        private final int capacity;
        private final double perMs;
        private final Map<String, Bucket> buckets = new HashMap<>();

        private static final class Bucket {
            private double tokens;
            private long lastRefillMs;

            Bucket(double tokens, long lastRefillMs) {
                this.tokens = tokens;
                this.lastRefillMs = lastRefillMs;
            }
        }

        private Limiter(int capacity, double refillPerMinute) {
            this.capacity = capacity;
            this.perMs = refillPerMinute / 60_000;
        }

        /** Takes one token from {@code key}'s bucket at {@code nowMs}, or refuses with how long to wait. */
        public synchronized void take(String key, long nowMs) {
            Bucket bucket = buckets.computeIfAbsent(key, missing -> new Bucket(capacity, nowMs));
            double gained = (nowMs - bucket.lastRefillMs) * perMs;
            bucket.tokens = Math.min(capacity, bucket.tokens + gained);
            bucket.lastRefillMs = nowMs;

            if (bucket.tokens < 1) {
                long seconds = Math.max(1, (long) Math.ceil((1 - bucket.tokens) / perMs / 1000));
                throw new TooManyRequestsException(seconds);
            }
            bucket.tokens -= 1;
        }

        /** The whole tokens left in {@code key}'s bucket; a caller never seen has a full one. */
        public synchronized int peek(String key) {
            Bucket bucket = buckets.get(key);
            return (int) Math.floor(bucket == null ? capacity : bucket.tokens);
        }

        /** How many callers have a bucket. */
        public synchronized int size() {
            return buckets.size();
        }
    }

    /**
     * Buckets holding {@code capacity} tokens, refilled at {@code refillPerMinute}.
     *
     * @throws ValidationException when the capacity is below one or the refill is not above zero
     */
    public static Limiter createLimiter(int capacity, double refillPerMinute) {
        if (capacity < 1) {
            throw ValidationException.field("capacity", "must be a whole number above zero, got " + capacity);
        }
        if (!Double.isFinite(refillPerMinute) || refillPerMinute <= 0) {
            throw ValidationException.field("refillPerMinute", "must be above zero");
        }
        return new Limiter(capacity, refillPerMinute);
    }

    /** Limits writes per bearer token, or per address for a caller without one. */
    public static void register(HttpApp app, Clock clock, int capacity, double refillPerMinute) {
        register(
                app,
                clock,
                capacity,
                refillPerMinute,
                (authorization, ip) -> authorization == null ? "ip:" + ip : authorization);
    }

    /**
     * Limits writes. Only writes are limited: a report that reloads is cheap and a check in is not.
     *
     * @param keyOf reads the caller a bucket belongs to from the authorization header (null when
     *     absent) and the caller's address
     */
    public static void register(
            HttpApp app, Clock clock, int capacity, double refillPerMinute, BiFunction<String, String, String> keyOf) {
        Limiter limiter = createLimiter(capacity, refillPerMinute);
        app.addRequestHook((request, response) -> {
            if (!WRITE_METHODS.contains(request.getMethod())) {
                return true;
            }
            try {
                limiter.take(
                        keyOf.apply(request.getHeader("authorization"), request.getRemoteAddr()),
                        clock.now().toEpochMilli());
                return true;
            } catch (TooManyRequestsException refused) {
                response.setStatus(429);
                response.setHeader("retry-after", Numbers.format(refused.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter()
                        .write(ApiJson.write(new ProblemBody(refused.code().wire(), refused.getMessage(), List.of())));
                return false;
            }
        });
    }
}
