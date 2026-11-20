package io.github.rkaushikdev.hitchline.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LoggerOptionsTest {

    private static final Config CONFIG = Config.read(Map.of());

    @Nested
    class TheOptions {

        @Test
        void carryTheLevelAndTheServiceName() {
            LoggerOptions options = LoggerOptions.of(CONFIG);
            assertThat(options.level()).isEqualTo(LogLevel.INFO);
            assertThat(options.base()).containsExactly(Map.entry("service", "hitchline"), Map.entry("store", "file"));
        }

        @Test
        void takeAServiceName() {
            assertThat(LoggerOptions.of(CONFIG, "hitchline-cli").base()).containsEntry("service", "hitchline-cli");
        }

        @Test
        void redactTheAuthorizationHeaderAndFreeText() {
            assertThat(LoggerOptions.REDACTED_PATHS).contains("req.headers.authorization");
            assertThat(LoggerOptions.REDACTED_PATHS).contains("body.note");
            assertThat(LoggerOptions.of(CONFIG).redact()).isEqualTo(LoggerOptions.REDACTED_PATHS);
        }

        @Test
        void areLeftOutAltogetherWhenAskedToBeSilent() {
            assertThat(LoggerOptions.forConfig(CONFIG.withLogLevel(LogLevel.SILENT)))
                    .isEmpty();
            assertThat(LoggerOptions.forConfig(CONFIG)).isPresent();
        }
    }

    @Nested
    class LevelChecks {

        @Test
        void letALevelThroughAtOrAboveTheThreshold() {
            assertThat(LoggerOptions.isEnabled(LogLevel.WARN, LogLevel.INFO)).isTrue();
            assertThat(LoggerOptions.isEnabled(LogLevel.INFO, LogLevel.INFO)).isTrue();
            assertThat(LoggerOptions.isEnabled(LogLevel.DEBUG, LogLevel.INFO)).isFalse();
        }

        @Test
        void letNothingThroughWhenSilent() {
            assertThat(LoggerOptions.isEnabled(LogLevel.SILENT, LogLevel.TRACE)).isFalse();
        }
    }

    @Nested
    class RequestLines {

        @Test
        void dropTheQueryStringAndRoundTheTime() {
            assertThat(LoggerOptions.requestLine("get", "/members/mem_1/adherence?window=x", 200, 12.4))
                    .isEqualTo("GET /members/mem_1/adherence 200 12ms");
        }

        @Test
        void handleAUrlWithNoQuery() {
            assertThat(LoggerOptions.requestLine("post", "/check-ins", 201, 3)).isEqualTo("POST /check-ins 201 3ms");
        }
    }
}
