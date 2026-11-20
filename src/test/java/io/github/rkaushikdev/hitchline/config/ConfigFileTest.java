package io.github.rkaushikdev.hitchline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    @TempDir
    Path directory;

    private Path fileWith(String body) throws IOException {
        Path path = directory.resolve("hitchline.json");
        Files.writeString(path, body, StandardCharsets.UTF_8);
        return path;
    }

    @Nested
    class Parsing {

        @Test
        void readsTheSettingsItKnows() {
            ConfigFile file = ConfigFile.parse("{\"store\":\"memory\",\"port\":9000,\"host\":\"0.0.0.0\"}");
            assertThat(file.values()).isEqualTo(Map.of("store", "memory", "port", 9000, "host", "0.0.0.0"));
        }

        @Test
        void readsAListOfOrigins() {
            ConfigFile file = ConfigFile.parse("{\"allowedOrigins\":[\"https://crew.example\"]}");
            assertThat(file.allowedOrigins()).containsExactly("https://crew.example");
        }

        @Test
        void refusesSomethingThatIsNotJson() {
            assertThatThrownBy(() -> ConfigFile.parse("nope")).hasMessageContaining("is not json");
        }

        @Test
        void refusesAListOrABareValue() {
            assertThatThrownBy(() -> ConfigFile.parse("[]")).hasMessageContaining("must be a json object");
            assertThatThrownBy(() -> ConfigFile.parse("\"hello\"")).hasMessageContaining("must be a json object");
        }

        @Test
        void refusesASettingItDoesNotKnow() {
            assertThatThrownBy(() -> ConfigFile.parse("{\"database\":\"postgres\"}"))
                    .hasMessageContaining("does not know the setting");
        }

        @Test
        void refusesAPortThatIsNotANumber() {
            assertThatThrownBy(() -> ConfigFile.parse("{\"port\":\"9000\"}")).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesOriginsThatAreNotAList() {
            assertThatThrownBy(() -> ConfigFile.parse("{\"allowedOrigins\":\"all\"}"))
                    .hasMessageContaining("must be a list");
        }
    }

    @Nested
    class TurningAFileIntoEnvironmentValues {

        @Test
        void namesEachSettingTheWayTheReaderExpects() {
            assertThat(ConfigFile.parse("{\"store\":\"memory\",\"port\":9000}").asEnvironment())
                    .isEqualTo(Map.of("HITCHLINE_STORE", "memory", "HITCHLINE_PORT", "9000"));
        }

        @Test
        void leavesOutAnythingTheFileDidNotSet() {
            assertThat(ConfigFile.parse("{}").asEnvironment()).isEmpty();
        }

        @Test
        void leavesTheOriginsOutSinceTheyAreNotAnEnvironmentSetting() {
            assertThat(ConfigFile.parse("{\"allowedOrigins\":[\"https://crew.example\"]}")
                            .asEnvironment())
                    .isEmpty();
        }
    }

    @Nested
    class Loading {

        @Test
        void readsTheEnvironmentAloneWhenThereIsNoFile() {
            LoadedConfig loaded = ConfigFile.load(Map.of("HITCHLINE_STORE", "memory"));
            assertThat(loaded.config().store()).isEqualTo(StoreKind.MEMORY);
            assertThat(loaded.from()).isEmpty();
        }

        @Test
        void readsAFileAndRemembersWhereItCameFrom() throws IOException {
            Path path = fileWith("{\"store\":\"memory\",\"port\":9100}");
            LoadedConfig loaded = ConfigFile.load(path, Map.of());
            assertThat(loaded.config().port()).isEqualTo(9100);
            assertThat(loaded.from()).hasValue(path.toString());
        }

        @Test
        void letsTheEnvironmentOverruleTheFile() throws IOException {
            Path path = fileWith("{\"store\":\"memory\",\"port\":9100}");
            LoadedConfig loaded = ConfigFile.load(path, Map.of("HITCHLINE_PORT", "9200"));
            assertThat(loaded.config().port()).isEqualTo(9200);
            assertThat(loaded.config().store()).isEqualTo(StoreKind.MEMORY);
        }

        @Test
        void carriesTheOriginsThrough() throws IOException {
            Path path = fileWith("{\"allowedOrigins\":[\"https://crew.example\"]}");
            assertThat(ConfigFile.load(path, Map.of()).allowedOrigins()).isEqualTo(List.of("https://crew.example"));
        }

        @Test
        void complainsAboutAFileThatIsNotThere() {
            assertThatThrownBy(() -> ConfigFile.load(Path.of("/nowhere/hitchline.json"), Map.of()))
                    .hasMessageContaining("no configuration file at");
        }

        @Test
        void stillValidatesWhatTheFileSaid() throws IOException {
            Path path = fileWith("{\"port\":99999}");
            assertThatThrownBy(() -> ConfigFile.load(path, Map.of())).hasMessageContaining("must be a port number");
        }
    }

    @Nested
    class TheWriteLimitInAFile {

        @Test
        void isReadAndTurnedIntoEnvironmentValues() {
            assertThat(ConfigFile.parse("{\"writesPerMinute\":600,\"burst\":90}")
                            .asEnvironment())
                    .isEqualTo(Map.of("HITCHLINE_WRITES_PER_MINUTE", "600", "HITCHLINE_BURST", "90"));
        }

        @Test
        void refusesOneThatIsNotANumber() {
            assertThatThrownBy(() -> ConfigFile.parse("{\"burst\":\"lots\"}")).hasMessageContaining("must be a number");
        }

        @Test
        void reachesTheConfigTheServerBuilds() throws IOException {
            Path path = fileWith("{\"writesPerMinute\":600,\"burst\":90}");
            LoadedConfig loaded = ConfigFile.load(path, Map.of());
            assertThat(loaded.config().writesPerMinute()).isEqualTo(600);
            assertThat(loaded.config().burst()).isEqualTo(90);
        }
    }
}
