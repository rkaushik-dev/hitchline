package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.http.Problems.ProblemBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The error mapping every route answers through: a domain failure answers with its status and its
 * problem body, a request no route takes answers 404 with the same shape, and anything else is a
 * 500 that says nothing about itself and is logged against the request.
 */
@RestControllerAdvice
public final class ProblemAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

    private final boolean logging;

    /** An error mapping that logs failures nobody expected when {@code logging} is on. */
    public ProblemAdvice(boolean logging) {
        this.logging = logging;
    }

    /** Answers a failure with its status and problem body. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemBody> failed(Exception error, HttpServletRequest request) {
        return answer(error, request);
    }

    /** Answers a request that no route takes, whatever its method. */
    @ExceptionHandler({
        NoHandlerFoundException.class,
        NoResourceFoundException.class,
        HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ProblemBody> noRoute(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(404)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProblemBody(
                        "not_found", "no route for " + request.getMethod() + " " + urlOf(request), List.of()));
    }

    /** Writes the answer for {@code error} straight to {@code response}, for a failure outside a route. */
    void write(RuntimeException error, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ResponseEntity<ProblemBody> answer = answer(error, request);
        response.setStatus(answer.getStatusCode().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ApiJson.write(answer.getBody()));
    }

    private ResponseEntity<ProblemBody> answer(Exception error, HttpServletRequest request) {
        int status = Problems.statusFor(error);
        if (status >= 500 && logging) {
            LOG.atError()
                    .setCause(error)
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("url", urlOf(request))
                    .log("request failed");
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Problems.problemFor(error));
    }

    /** The path and query string the request asked for. */
    static String urlOf(HttpServletRequest request) {
        String query = request.getQueryString();
        return request.getRequestURI() + (query == null ? "" : "?" + query);
    }
}
