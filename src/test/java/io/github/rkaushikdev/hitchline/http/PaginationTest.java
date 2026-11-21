package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.Pagination.Page;
import io.github.rkaushikdev.hitchline.http.Pagination.PageBody;
import io.github.rkaushikdev.hitchline.http.Pagination.PageRequest;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PaginationTest {

    private record Row(String id) {}

    private static final List<Row> ROWS = IntStream.range(0, 10)
            .mapToObj(index -> new Row("row_" + Texts.padStart(String.valueOf(index), 2, '0')))
            .toList();

    private static List<String> keys(Page<Row> page) {
        return page.items().stream().map(Row::id).toList();
    }

    @Nested
    class Cursors {

        @Test
        void roundTripsAKey() {
            assertThat(Pagination.decodeCursor(Pagination.encodeCursor("row_03")))
                    .isEqualTo("row_03");
        }

        @Test
        void isUrlSafe() {
            assertThat(Pagination.encodeCursor("a/b+c?d")).doesNotContainPattern("[/+=]");
        }

        @Test
        void refusesAnEmptyCursor() {
            assertThatThrownBy(() -> Pagination.decodeCursor("")).isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class ReadingTheQuery {

        @Test
        void defaultsTheLimit() {
            assertThat(Pagination.readPageRequest(Map.of()))
                    .isEqualTo(new PageRequest(Pagination.DEFAULT_LIMIT, Optional.empty()));
        }

        @Test
        void takesALimitAndACursor() {
            assertThat(Pagination.readPageRequest(Map.of("limit", "10", "after", "abc")))
                    .isEqualTo(PageRequest.after(10, "abc"));
        }

        @Test
        void refusesALimitOutOfRange() {
            assertThatThrownBy(() -> Pagination.readPageRequest(Map.of("limit", "0")))
                    .hasMessageContaining("between 1 and 500");
            assertThatThrownBy(
                            () -> Pagination.readPageRequest(Map.of("limit", String.valueOf(Pagination.MAX_LIMIT + 1))))
                    .hasMessageContaining("between 1 and 500");
            assertThatThrownBy(() -> Pagination.readPageRequest(Map.of("limit", "lots")))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class Paging {

        @Test
        void takesTheFirstPageAndOffersACursor() {
            Page<Row> page = Pagination.paginate(ROWS, PageRequest.first(3), Row::id);
            assertThat(keys(page)).containsExactly("row_00", "row_01", "row_02");
            assertThat(page.total()).isEqualTo(10);
            assertThat(page.nextCursor()).hasValue(Pagination.encodeCursor("row_02"));
        }

        @Test
        void carriesOnFromACursor() {
            Page<Row> page =
                    Pagination.paginate(ROWS, PageRequest.after(3, Pagination.encodeCursor("row_02")), Row::id);
            assertThat(keys(page)).containsExactly("row_03", "row_04", "row_05");
        }

        @Test
        void offersNoCursorOnTheLastPage() {
            Page<Row> page =
                    Pagination.paginate(ROWS, PageRequest.after(4, Pagination.encodeCursor("row_05")), Row::id);
            assertThat(keys(page)).containsExactly("row_06", "row_07", "row_08", "row_09");
            assertThat(page.nextCursor()).isEmpty();
        }

        @Test
        void handlesALimitLargerThanTheList() {
            Page<Row> page = Pagination.paginate(ROWS, PageRequest.first(100), Row::id);
            assertThat(page.items()).hasSize(10);
            assertThat(page.nextCursor()).isEmpty();
        }

        @Test
        void pagesAnEmptyList() {
            Page<Row> page = Pagination.paginate(List.<Row>of(), PageRequest.first(10), Row::id);
            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        void refusesACursorNamingARowThatHasGone() {
            assertThatThrownBy(() ->
                            Pagination.paginate(ROWS, PageRequest.after(3, Pagination.encodeCursor("row_99")), Row::id))
                    .hasMessageContaining("no longer in this list");
        }

        @Test
        void doesNotMoveARowWhenOneIsAppended() {
            Page<Row> first = Pagination.paginate(ROWS, PageRequest.first(3), Row::id);
            List<Row> grown = new ArrayList<>(ROWS);
            grown.add(new Row("row_10"));
            Page<Row> second = Pagination.paginate(grown, new PageRequest(3, first.nextCursor()), Row::id);
            assertThat(keys(second)).containsExactly("row_03", "row_04", "row_05");
        }
    }

    @Nested
    class TheBodyShape {

        @Test
        void writesItemsACursorAndATotal() {
            Page<Row> page = Pagination.paginate(ROWS, PageRequest.first(2), Row::id);
            assertThat(Pagination.pageBody(page, Row::id))
                    .isEqualTo(new PageBody<>(
                            List.of("row_00", "row_01"), Optional.of(Pagination.encodeCursor("row_01")), 10));
        }

        @Test
        void writesANullCursorAtTheEnd() {
            Page<Row> page = Pagination.paginate(ROWS, PageRequest.first(20), Row::id);
            assertThat(Pagination.pageBody(page, Row::id).nextCursor()).isEmpty();
        }
    }
}
