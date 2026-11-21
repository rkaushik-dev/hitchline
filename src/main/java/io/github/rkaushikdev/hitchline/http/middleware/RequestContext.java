package io.github.rkaushikdev.hitchline.http.middleware;

import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.infra.LoggerOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A request id on every line. A crew member reporting that "the app said no" can read the id off
 * the screen, and that id has to appear on the log line for the request that said it.
 */
public final class RequestContext {

    /** The header a request id travels in, both ways. */
    public static final String REQUEST_ID_HEADER = "x-request-id";

    /** The request attribute holding the request id. */
    public static final String REQUEST_ID = RequestContext.class.getName() + ".id";

    /** The request attribute holding when the request started, in milliseconds. */
    public static final String STARTED_AT_MS = RequestContext.class.getName() + ".startedAtMs";

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Logger LOG = LoggerFactory.getLogger(RequestContext.class);

    private RequestContext() {}

    /** A caller supplied id, kept only when it looks like one. */
    public static Optional<String> readRequestId(String header) {
        return header != null && ID.matcher(header).matches() ? Optional.of(header) : Optional.empty();
    }

    /** A caller supplied id, kept only when the header came once and looks like one. */
    public static Optional<String> readRequestId(List<String> values) {
        return values.size() == 1 ? readRequestId(values.get(0)) : Optional.empty();
    }

    /** Gives every request an id on the process's own monotonic clock. */
    public static void register(HttpApp app, Supplier<String> nextId) {
        register(app, nextId, () -> System.nanoTime() / 1_000_000.0);
    }

    /**
     * Gives every request an id, answers with it, and logs one line per request carrying it.
     *
     * @param nextId mints an id when the caller did not send one
     * @param now reads a monotonic time in milliseconds
     */
    public static void register(HttpApp app, Supplier<String> nextId, DoubleSupplier now) {
        app.addRequestHook((request, response) -> {
            String id = readRequestId(Collections.list(request.getHeaders(REQUEST_ID_HEADER)))
                    .orElseGet(nextId);
            request.setAttribute(REQUEST_ID, id);
            request.setAttribute(STARTED_AT_MS, now.getAsDouble());
            response.setHeader(REQUEST_ID_HEADER, id);
            return true;
        });

        app.addResponseHook((request, response, elapsed) -> {
            double started = request.getAttribute(STARTED_AT_MS) instanceof Double value ? value : now.getAsDouble();
            String url =
                    request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            LOG.atInfo()
                    .addKeyValue("requestId", idOf(request).orElse(""))
                    .addKeyValue("status", response.getStatus())
                    .log(LoggerOptions.requestLine(
                            request.getMethod(), url, response.getStatus(), now.getAsDouble() - started));
        });
    }

    /** The id the request is known by, once a request context has given it one. */
    public static Optional<String> idOf(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ID) instanceof String id ? Optional.of(id) : Optional.empty();
    }

    /** How long a request has been running at {@code nowMs}; zero when nothing was recorded. */
    public static double elapsedMs(HttpServletRequest request, double nowMs) {
        return request.getAttribute(STARTED_AT_MS) instanceof Double started ? Math.max(0, nowMs - started) : 0;
    }
}
