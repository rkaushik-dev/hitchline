package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

class QueryTest {

    private static MockHttpServletRequest request(Map<String, String> query) {
        return request(query, Map.of());
    }

    private static MockHttpServletRequest request(Map<String, String> query, Map<String, String> params) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        query.forEach(request::addParameter);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, params);
        return request;
    }

    @Nested
    class Windows {

        @Test
        void parsesOneFromTheQuery() {
            assertThat(Query.windowOf(request(Map.of("window", "2025-07-01..2025-07-14")))
                            .toString())
                    .isEqualTo("2025-07-01..2025-07-14");
        }

        @Test
        void saysWhatItWasNeededFor() {
            assertThatThrownBy(() -> Query.windowOf(request(Map.of()), "read a roster"))
                    .hasMessageContaining("is needed to read a roster");
        }

        @Test
        void handsBackTheRawStringWhenAServiceWantsOne() {
            assertThat(Query.windowStringOf(request(Map.of("window", "2025-07-01..2025-07-14"))))
                    .isEqualTo("2025-07-01..2025-07-14");
        }

        @Test
        void isHappyWithNoWindowWhenItIsOptional() {
            assertThat(Query.optionalWindowOf(request(Map.of()))).isEmpty();
            assertThat(Query.optionalWindowOf(request(Map.of("window", "2025-07-01..2025-07-14"))))
                    .isPresent();
        }

        @Test
        void stillRefusesAWindowItCannotRead() {
            assertThatThrownBy(() -> Query.windowOf(request(Map.of("window", "july"))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void ignoresARepeatedParameterRatherThanGuessing() {
            MockHttpServletRequest repeated = request(Map.of());
            repeated.addParameter("window", "a", "b");
            assertThatThrownBy(() -> Query.windowOf(repeated)).hasMessageContaining("is needed");
        }
    }

    @Nested
    class Dates {

        @Test
        void parsesOneFromTheQuery() {
            assertThat(Query.dateOf(request(Map.of("on", "2025-07-01"))).toString())
                    .isEqualTo("2025-07-01");
        }

        @Test
        void saysWhatItWasNeededFor() {
            assertThatThrownBy(() -> Query.dateOf(request(Map.of()), "on", "find a hitch"))
                    .hasMessageContaining("is needed to find a hitch");
        }

        @Test
        void handsBackTheRawStringToo() {
            assertThat(Query.rawDateOf(request(Map.of("on", "2025-07-01")))).isEqualTo("2025-07-01");
        }
    }

    @Nested
    class TheIds {

        @Test
        void readsThemOutOfThePath() {
            MockHttpServletRequest req = request(
                    Map.of(),
                    Map.of(
                            "memberId", "mem_0123456789ABCDEF",
                            "habitId", "hab_0123456789ABCDEF",
                            "crewId", "crw_0123456789ABCDEF"));
            assertThat(Query.memberOf(req)).isEqualTo("mem_0123456789ABCDEF");
            assertThat(Query.habitOf(req)).isEqualTo("hab_0123456789ABCDEF");
            assertThat(Query.crewOf(req)).isEqualTo("crw_0123456789ABCDEF");
        }

        @Test
        void refusesOneThatIsNotAnId() {
            assertThatThrownBy(() -> Query.memberOf(request(Map.of(), Map.of("memberId", "nobody"))))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void readsAMemberOutOfTheQueryWhenThatIsWhereItIs() {
            assertThat(Query.queryMemberOf(request(Map.of("memberId", "mem_0123456789ABCDEF"))))
                    .isEqualTo("mem_0123456789ABCDEF");
            assertThatThrownBy(() -> Query.queryMemberOf(request(Map.of()), "list habits"))
                    .hasMessageContaining("is needed to list habits");
        }
    }
}
