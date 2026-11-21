package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Cursor paging. Offsets are wrong for a list that is still being appended to: a crew member
 * checking in while a supervisor pages through the list would push a row from page one onto page
 * two. A cursor names the last row seen, so a new row at the end never moves an old one.
 */
public final class Pagination {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    /**
     * What a caller asked for.
     *
     * @param limit rows per page
     * @param after the cursor of the page before, if this is not the first page
     */
    public record PageRequest(int limit, Optional<String> after) {

        public PageRequest {
            Objects.requireNonNull(after, "after");
        }

        /** The first page, {@code limit} rows long. */
        public static PageRequest first(int limit) {
            return new PageRequest(limit, Optional.empty());
        }

        /** The page after {@code cursor}, {@code limit} rows long. */
        public static PageRequest after(int limit, String cursor) {
            return new PageRequest(limit, Optional.of(cursor));
        }
    }

    /**
     * One page cut out of an ordered list.
     *
     * @param items the rows on this page
     * @param nextCursor the cursor of the next page, when there is one
     * @param total how many rows the whole list holds
     * @param <T> the row type
     */
    public record Page<T>(List<T> items, Optional<String> nextCursor, int total) {

        public Page {
            items = List.copyOf(items);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    /**
     * The body shape every paged endpoint answers with.
     *
     * @param items the rows on this page, as they are written
     * @param nextCursor the cursor of the next page, written as null on the last one
     * @param total how many rows the whole list holds
     * @param <V> the written row type
     */
    public record PageBody<V>(List<V> items, Optional<String> nextCursor, int total) {

        public PageBody {
            items = List.copyOf(items);
            Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    private Pagination() {}

    /** The cursor naming the row whose key is {@code key}: its UTF-8 bytes in url-safe base64. */
    public static String encodeCursor(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    /** The row key a cursor names. */
    public static String decodeCursor(String cursor) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            decoded = "";
        }
        if (decoded.isEmpty()) {
            throw ValidationException.field("after", "is not a cursor this api handed out");
        }
        return decoded;
    }

    /** Reads {@code limit} and {@code after} from a request's query string. */
    public static PageRequest readPageRequest(HttpServletRequest request) {
        return readPageRequest(Query.singleValued(request));
    }

    /** Reads {@code limit} and {@code after} from query parameters, the limit defaulting to 50. */
    public static PageRequest readPageRequest(Map<String, String> query) {
        String raw = query.get("limit");
        double limit = raw == null ? DEFAULT_LIMIT : Numbers.parse(raw);
        if (!Numbers.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
            throw ValidationException.field("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return new PageRequest((int) limit, Optional.ofNullable(query.get("after")));
    }

    /**
     * Cuts a page out of an ordered list. The list has to be in a stable order already, which every
     * repository read here guarantees.
     */
    public static <T> Page<T> paginate(List<T> items, PageRequest request, Function<T, String> keyOf) {
        int start = 0;
        if (request.after().isPresent()) {
            String wanted = decodeCursor(request.after().get());
            int at = -1;
            for (int index = 0; index < items.size(); index += 1) {
                if (keyOf.apply(items.get(index)).equals(wanted)) {
                    at = index;
                    break;
                }
            }
            if (at < 0) {
                throw ValidationException.field("after", "names a row that is no longer in this list");
            }
            start = at + 1;
        }

        List<T> slice = items.subList(start, Math.min(items.size(), start + request.limit()));
        boolean more = start + slice.size() < items.size();
        Optional<String> next = more && !slice.isEmpty()
                ? Optional.of(encodeCursor(keyOf.apply(slice.get(slice.size() - 1))))
                : Optional.empty();
        return new Page<>(slice, next, items.size());
    }

    /** The page as it is written, each row through {@code view}. */
    public static <T, V> PageBody<V> pageBody(Page<T> page, Function<T, V> view) {
        return new PageBody<>(page.items().stream().map(view).toList(), page.nextCursor(), page.total());
    }
}
