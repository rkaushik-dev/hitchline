package io.github.rkaushikdev.hitchline.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One http application being put together: its routes, the steps every request goes through and
 * the error mapping. Modules register into it the way they always have, a module at a time; a
 * server then serves it, and a test drives it without a socket.
 *
 * <p>Every request goes through the same lifecycle: the {@linkplain RequestHook request hooks} in
 * the order they were added, any of which may answer the request itself; then the route, or the
 * {@linkplain #options(Answer) preflight answer} for an {@code OPTIONS} request; then the
 * {@linkplain ResponseHook response hooks}, which see every response, whoever wrote it.
 */
public final class HttpApp {

    /** A step run before the route. */
    @FunctionalInterface
    public interface RequestHook {

        /**
         * Looks at the request before its route runs.
         *
         * @return true to carry on, false when this hook answered the request itself
         */
        boolean onRequest(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    /** Writes the whole response to a request. */
    @FunctionalInterface
    public interface Answer {

        void answer(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    /** A step run once the response has been written, whatever wrote it. */
    @FunctionalInterface
    public interface ResponseHook {

        /**
         * Sees the finished response.
         *
         * @param elapsedMs how long the request took, in milliseconds
         */
        void onResponse(HttpServletRequest request, HttpServletResponse response, double elapsedMs);
    }

    private final List<Object> routes = new ArrayList<>();
    private final List<RequestHook> requestHooks = new ArrayList<>();
    private final List<ResponseHook> responseHooks = new ArrayList<>();
    private final ProblemAdvice errors;
    private Answer preflight;

    HttpApp(ProblemAdvice errors) {
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** Adds a {@code @RestController} whose mappings become routes. */
    public HttpApp addRoutes(Object controller) {
        routes.add(Objects.requireNonNull(controller, "controller"));
        return this;
    }

    /** Adds a step every request goes through before its route, after the ones already added. */
    public HttpApp addRequestHook(RequestHook hook) {
        requestHooks.add(Objects.requireNonNull(hook, "hook"));
        return this;
    }

    /** Adds a step that sees every finished response, after the ones already added. */
    public HttpApp addResponseHook(ResponseHook hook) {
        responseHooks.add(Objects.requireNonNull(hook, "hook"));
        return this;
    }

    /** Answers every {@code OPTIONS} request, on any path, once the request hooks have run. */
    public HttpApp options(Answer answer) {
        this.preflight = Objects.requireNonNull(answer, "answer");
        return this;
    }

    /** The controllers, in the order they were added. */
    public List<Object> routes() {
        return List.copyOf(routes);
    }

    /** The error mapping every route answers through. */
    public ProblemAdvice errors() {
        return errors;
    }

    /** The message converters the routes read and write with: plain text, then the api's JSON. */
    public List<HttpMessageConverter<?>> converters() {
        return List.of(new StringHttpMessageConverter(StandardCharsets.UTF_8), new ApiJson());
    }

    /** The servlet filter that runs the lifecycle around the routes. */
    public OncePerRequestFilter lifecycle() {
        return new Lifecycle();
    }

    /** Every registered route as {@code METHOD path}, one per line, in registration order. */
    public String printRoutes() {
        List<String> lines = new ArrayList<>();
        for (Object controller : routes) {
            RequestMapping base =
                    AnnotatedElementUtils.findMergedAnnotation(controller.getClass(), RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method method : controller.getClass().getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (RequestMethod verb : mapping.method()) {
                    for (String path : mapping.path()) {
                        lines.add(verb + " " + prefix + path);
                    }
                }
            }
        }
        if (preflight != null) {
            lines.add("OPTIONS /*");
        }
        return String.join("\n", lines);
    }

    private final class Lifecycle extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            long started = System.nanoTime();
            try {
                if (runRequestHooks(request, response)) {
                    Optional<Answer> answer =
                            "OPTIONS".equals(request.getMethod()) ? Optional.ofNullable(preflight) : Optional.empty();
                    if (answer.isPresent()) {
                        answer.get().answer(request, response);
                    } else {
                        chain.doFilter(request, response);
                    }
                }
            } finally {
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                for (ResponseHook hook : responseHooks) {
                    hook.onResponse(request, response, elapsed);
                }
            }
        }

        private boolean runRequestHooks(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                for (RequestHook hook : requestHooks) {
                    if (!hook.onRequest(request, response)) {
                        return false;
                    }
                }
                return true;
            } catch (RuntimeException failed) {
                errors.write(failed, request, response);
                return false;
            }
        }
    }
}
