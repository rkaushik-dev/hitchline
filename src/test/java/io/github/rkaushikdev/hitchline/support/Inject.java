package io.github.rkaushikdev.hitchline.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.http.ApiJson;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Sends requests into an {@link HttpApp} without a socket, the way a route test does: the same
 * routes, lifecycle, error mapping and converters a server would use, driven through MockMvc.
 */
public final class Inject {

    private final MockMvc mvc;

    private Inject(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** An injector over everything {@code app} holds now. */
    public static Inject into(HttpApp app) {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(app.routes().toArray())
                .setControllerAdvice(app.errors())
                .setMessageConverters(app.converters().toArray(HttpMessageConverter<?>[]::new))
                .addFilters(app.lifecycle())
                .build();
        return new Inject(mvc);
    }

    /**
     * What a request got back.
     *
     * @param status the http status
     * @param headers every header, by lower-case name
     * @param body the body text
     */
    public record Reply(int status, Map<String, String> headers, String body) {

        /** The body read as JSON. */
        public JsonNode json() {
            return Json.parse(body);
        }

        /** The header {@code name}, when the response carried it. */
        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /** A JSON object from alternating names and values; a value may be null. */
    public static ObjectNode body(Object... namesAndValues) {
        ObjectNode written = Json.object();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            written.set((String) namesAndValues[index], ApiJson.toTree(namesAndValues[index + 1]));
        }
        return written;
    }

    public Reply get(String url) {
        return send("GET", url, Map.of(), null);
    }

    public Reply get(String url, Map<String, String> headers) {
        return send("GET", url, headers, null);
    }

    /** Sends one request; a payload goes as a JSON body. */
    public Reply send(String method, String url, Map<String, String> headers, JsonNode payload) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), url);
        headers.forEach(request::header);
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(Json.stringify(payload));
        }
        try {
            MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
            Map<String, String> written = new LinkedHashMap<>();
            for (String name : response.getHeaderNames()) {
                written.put(name.toLowerCase(Locale.ROOT), response.getHeader(name));
            }
            return new Reply(response.getStatus(), written, response.getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception failed) {
            throw new IllegalStateException("the request could not be sent: " + method + " " + url, failed);
        }
    }

    /** A request carrying {@code Authorization: Bearer <token>}. */
    public Reply call(String method, String url, String token) {
        return send(method, url, Map.of("authorization", "Bearer " + token), null);
    }

    /** A request carrying a bearer token and a JSON body. */
    public Reply call(String method, String url, String token, JsonNode payload) {
        return send(method, url, Map.of("authorization", "Bearer " + token), payload);
    }
}
