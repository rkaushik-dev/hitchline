package io.github.rkaushikdev.hitchline.http;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Request and response bodies as JSON. A body arrives as a {@link JsonNode} for the route's schema
 * to check; a response is turned into a tree (records by their components, an absent
 * {@link java.util.Optional} as null, a {@link WireValue} as its word) and written in the
 * canonical form of {@link Json}, so a ratio prints {@code 0.67} and a whole number prints
 * without a fraction.
 */
public final class ApiJson extends AbstractHttpMessageConverter<Object> {

    private static final ObjectMapper MAPPER = Json.mapper()
            .copy()
            .registerModule(new Jdk8Module())
            .registerModule(new SimpleModule("wire-values").addSerializer(WireValue.class, new WireSerializer()));

    /** Reads and writes {@code application/json}. */
    public ApiJson() {
        super(StandardCharsets.UTF_8, MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
    }

    /** {@code body} as the tree it is written as. */
    public static JsonNode toTree(Object body) {
        return body instanceof JsonNode tree ? tree : MAPPER.valueToTree(body);
    }

    /** {@code body} as the text it is written as. */
    public static String write(Object body) {
        return Json.stringify(toTree(body));
    }

    @Override
    protected boolean supports(Class<?> type) {
        return true;
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return mediaType != null && super.canRead(mediaType);
    }

    @Override
    public boolean canRead(Class<?> type, MediaType mediaType) {
        return JsonNode.class.isAssignableFrom(type) && canRead(mediaType);
    }

    @Override
    protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
        String text = new String(input.getBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return Json.parse(text);
        } catch (RuntimeException unreadable) {
            throw new HttpMessageNotReadableException(unreadable.getMessage(), unreadable, input);
        }
    }

    @Override
    protected void writeInternal(Object body, HttpOutputMessage output) throws IOException {
        output.getBody().write(write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static final class WireSerializer extends StdSerializer<WireValue> {

        WireSerializer() {
            super(WireValue.class);
        }

        @Override
        public void serialize(WireValue value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(value.wire());
        }
    }
}
