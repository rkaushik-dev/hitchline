package io.github.rkaushikdev.hitchline.http.middleware;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.ApiJson;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Problems.ProblemBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;

/**
 * The headers a json api should send and the limits it should hold to. None of this is clever; all
 * of it is the sort of thing that only gets noticed in a penetration test report six months after
 * the fact.
 */
public final class Hardening {

    /** Sent on every response, an error or a preflight included. */
    public static final Map<String, String> SECURITY_HEADERS;

    static {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-content-type-options", "nosniff");
        headers.put("x-frame-options", "DENY");
        headers.put("referrer-policy", "no-referrer");
        headers.put("cross-origin-resource-policy", "same-origin");
        headers.put("cache-control", "no-store");
        SECURITY_HEADERS = Collections.unmodifiableMap(headers);
    }

    /** The largest body accepted when nothing else is said, in bytes. */
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;

    private Hardening() {}

    /** Refuses a body limit below a kilobyte or above eight megabytes. */
    public static void checkBodyLimit(long bytes) {
        if (bytes < 1_024 || bytes > 8 * 1024 * 1024) {
            throw ValidationException.field("maxBodyBytes", "must be between a kilobyte and eight megabytes");
        }
    }

    /** True when {@code origin} is on the list; a request with no origin is never allowed. */
    public static boolean isAllowedOrigin(String origin, List<String> allowed) {
        return origin != null && allowed.contains(origin);
    }

    /** Hardens {@code app} with the default body limit. */
    public static void register(HttpApp app, List<String> allowedOrigins) {
        register(app, DEFAULT_MAX_BODY_BYTES, allowedOrigins);
    }

    /**
     * Sends the security headers on every response, echoes an allowed origin, and answers browser
     * preflights from allowed origins.
     *
     * @param maxBodyBytes the largest body accepted, in bytes
     * @param allowedOrigins origins allowed to call the api from a browser
     */
    public static void register(HttpApp app, long maxBodyBytes, List<String> allowedOrigins) {
        List<String> allowed = List.copyOf(allowedOrigins);
        checkBodyLimit(maxBodyBytes);

        app.addRequestHook((request, response) -> {
            SECURITY_HEADERS.forEach(response::setHeader);
            String origin = request.getHeader("origin");
            if (isAllowedOrigin(origin, allowed)) {
                response.setHeader("access-control-allow-origin", origin);
                response.setHeader("vary", "origin");
            }
            return true;
        });

        app.options((request, response) -> {
            if (!isAllowedOrigin(request.getHeader("origin"), allowed)) {
                writeNotFound(response);
                return;
            }
            response.setHeader("access-control-allow-methods", "GET,POST,PUT,PATCH,DELETE");
            response.setHeader("access-control-allow-headers", "authorization,content-type,x-request-id");
            response.setHeader("access-control-max-age", "600");
            response.setStatus(204);
        });
    }

    private static void writeNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(404);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiJson.write(new ProblemBody("not_found", "no cors here", List.of())));
    }
}
