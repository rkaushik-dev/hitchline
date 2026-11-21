package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.Metrics.Counter;
import io.github.rkaushikdev.hitchline.http.Metrics.Histogram;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Nested
    class Counters {

        @Test
        void countsUpFromZero() {
            Metrics metrics = Metrics.create();
            Counter requests = metrics.counter("http_requests_total", "requests served");
            assertThat(requests.read()).isZero();
            requests.add();
            requests.add(2);
            assertThat(requests.read()).isEqualTo(3);
        }

        @Test
        void keepsLabelSetsApart() {
            Metrics metrics = Metrics.create();
            Counter requests = metrics.counter("http_requests_total", "requests served");
            requests.add(1, Map.of("status", "200"));
            requests.add(1, Map.of("status", "404"));
            assertThat(requests.read(Map.of("status", "200"))).isEqualTo(1);
            assertThat(requests.read(Map.of("status", "404"))).isEqualTo(1);
            assertThat(requests.read()).isZero();
        }

        @Test
        void doesNotCareWhatOrderLabelsAreGivenIn() {
            Metrics metrics = Metrics.create();
            Counter requests = metrics.counter("http_requests_total", "requests served");
            requests.add(1, Map.of("method", "GET", "status", "200"));
            assertThat(requests.read(Map.of("status", "200", "method", "GET"))).isEqualTo(1);
        }

        @Test
        void handsBackTheSameCounterForTheSameName() {
            Metrics metrics = Metrics.create();
            metrics.counter("http_requests_total", "requests served").add(2);
            assertThat(metrics.counter("http_requests_total", "requests served").read())
                    .isEqualTo(2);
        }

        @Test
        void refusesANameThatIsNotAMetricName() {
            Metrics metrics = Metrics.create();
            assertThatThrownBy(() -> metrics.counter("HTTP Requests", "no")).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesToReuseANameForADifferentKind() {
            Metrics metrics = Metrics.create();
            metrics.counter("http_requests_total", "requests served");
            assertThatThrownBy(() -> metrics.histogram("http_requests_total", "no", List.of(1.0)))
                    .hasMessageContaining("already a counter");
        }
    }

    @Nested
    class Histograms {

        @Test
        void countsAndSumsWhatItSees() {
            Metrics metrics = Metrics.create();
            Histogram latency = metrics.histogram("http_latency_ms", "how long", List.of(10.0, 100.0));
            latency.observe(5);
            latency.observe(50);
            latency.observe(500);
            assertThat(latency.count()).isEqualTo(3);
            assertThat(latency.sum()).isEqualTo(555);
        }

        @Test
        void rendersCumulativeBuckets() {
            Metrics metrics = Metrics.create();
            Histogram latency = metrics.histogram("http_latency_ms", "how long", List.of(10.0, 100.0));
            latency.observe(5);
            latency.observe(50);
            latency.observe(500);
            String text = metrics.render();
            assertThat(text).contains("http_latency_ms_bucket{le=\"10\"} 1");
            assertThat(text).contains("http_latency_ms_bucket{le=\"100\"} 2");
            assertThat(text).contains("http_latency_ms_bucket{le=\"+Inf\"} 3");
            assertThat(text).contains("http_latency_ms_count 3");
            assertThat(text).contains("http_latency_ms_sum 555");
        }

        @Test
        void keepsLabelsOnTheBuckets() {
            Metrics metrics = Metrics.create();
            Histogram latency = metrics.histogram("http_latency_ms", "how long", List.of(10.0));
            latency.observe(5, Map.of("route", "/health"));
            assertThat(metrics.render()).contains("http_latency_ms_bucket{route=\"/health\",le=\"10\"} 1");
        }

        @Test
        void sortsTheBoundsItWasGiven() {
            Metrics metrics = Metrics.create();
            Histogram latency = metrics.histogram("http_latency_ms", "how long", List.of(100.0, 10.0));
            latency.observe(50);
            List<String> lines = Texts.split(metrics.render(), "\n");
            assertThat(lines.get(2)).contains("le=\"10\"");
            assertThat(lines.get(3)).contains("le=\"100\"");
        }
    }

    @Nested
    class Rendering {

        @Test
        void writesNothingForAnEmptyRegistry() {
            assertThat(Metrics.create().render()).isEmpty();
        }

        @Test
        void writesAHelpAndATypeLineForEachMetric() {
            Metrics metrics = Metrics.create();
            metrics.counter("http_requests_total", "requests served").add();
            List<String> lines = Texts.split(metrics.render(), "\n");
            assertThat(lines.get(0)).isEqualTo("# HELP http_requests_total requests served");
            assertThat(lines.get(1)).isEqualTo("# TYPE http_requests_total counter");
            assertThat(lines.get(2)).isEqualTo("http_requests_total 1");
        }

        @Test
        void sortsMetricsByName() {
            Metrics metrics = Metrics.create();
            metrics.counter("zeta_total", "z").add();
            metrics.counter("alpha_total", "a").add();
            assertThat(metrics.render().indexOf("alpha_total"))
                    .isLessThan(metrics.render().indexOf("zeta_total"));
        }

        @Test
        void escapesALabelValueWithAQuoteInIt() {
            Metrics metrics = Metrics.create();
            metrics.counter("http_requests_total", "requests served").add(1, Map.of("route", "a\"b"));
            assertThat(metrics.render()).contains("route=\"a\\\"b\"");
        }

        @Test
        void offersLatencyBoundsThatSuitASmallApi() {
            assertThat(Metrics.LATENCY_BOUNDS.get(0)).isEqualTo(5);
            assertThat(Metrics.LATENCY_BOUNDS.get(Metrics.LATENCY_BOUNDS.size() - 1))
                    .isEqualTo(1_000);
        }
    }
}
