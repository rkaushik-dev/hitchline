package io.github.rkaushikdev.hitchline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.infra.store.Stores;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @TempDir
    Path directory;

    private static Config defaults() {
        return Config.read(Map.of());
    }

    private Config onFile(String name) {
        return defaults()
                .withStore(StoreKind.FILE)
                .withStorePath(directory.resolve(name).toString());
    }

    @Nested
    class Defaults {

        @Test
        void readsAConfigFromAnEmptyEnvironment() {
            Config config = defaults();
            assertThat(config.store()).isEqualTo(Config.DEFAULT_STORE);
            assertThat(config.port()).isEqualTo(Config.DEFAULT_PORT);
            assertThat(config.logLevel()).isEqualTo(LogLevel.INFO);
            assertThat(config.tokens()).isEmpty();
        }

        @Test
        void describesItselfInOneLine() {
            assertThat(defaults().describe())
                    .isEqualTo("127.0.0.1:8417 on " + Config.DEFAULT_STORE_PATH + ", 0 tokens");
        }
    }

    @Nested
    class ReadingTheEnvironment {

        @Test
        void takesTheStoreKindAndPath() {
            Config config = Config.read(Map.of("HITCHLINE_STORE", "memory", "HITCHLINE_STORE_PATH", "/tmp/x.jsonl"));
            assertThat(config.store()).isEqualTo(StoreKind.MEMORY);
            assertThat(config.storePath()).isEqualTo("/tmp/x.jsonl");
        }

        @Test
        void refusesAStoreKindItDoesNotKnow() {
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_STORE", "postgres")))
                    .hasMessageContaining("must be memory or file");
        }

        @Test
        void refusesAnEmptyStorePath() {
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_STORE_PATH", "  ")))
                    .hasMessageContaining("must not be empty");
        }

        @Test
        void takesTheHostAndPort() {
            Config config = Config.read(Map.of("HITCHLINE_HOST", "0.0.0.0", "HITCHLINE_PORT", "9000"));
            assertThat(config.host()).isEqualTo("0.0.0.0");
            assertThat(config.port()).isEqualTo(9000);
        }

        @Test
        void refusesAPortThatIsNotOne() {
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_PORT", "eighty")))
                    .hasMessageContaining("must be a port number");
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_PORT", "70000")))
                    .hasMessageContaining("must be a port number");
        }

        @Test
        void takesALogLevelAndRefusesAnInventedOne() {
            assertThat(Config.read(Map.of("HITCHLINE_LOG_LEVEL", "DEBUG")).logLevel())
                    .isEqualTo(LogLevel.DEBUG);
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_LOG_LEVEL", "chatty")))
                    .hasMessageContaining("is not a level");
        }

        @Test
        void checksTheDefaultZoneAgainstThePlatform() {
            assertThat(Config.read(Map.of("HITCHLINE_DEFAULT_ZONE", "Europe/Oslo"))
                            .defaultZone())
                    .isEqualTo("Europe/Oslo");
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_DEFAULT_ZONE", "North Sea/Alpha")))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class Tokens {

        @Test
        void readsOneEntry() {
            assertThat(Config.parseTokens("abcdefghijklmnop:mem_0123456789ABCDEF:read|write"))
                    .containsExactly(
                            new ApiToken("abcdefghijklmnop", "mem_0123456789ABCDEF", List.of("read", "write")));
        }

        @Test
        void defaultsTheScopeToRead() {
            assertThat(Config.parseTokens("abcdefghijklmnop:mem_1").get(0).scopes())
                    .containsExactly("read");
        }

        @Test
        void readsSeveralSeparatedByCommas() {
            assertThat(Config.parseTokens("abcdefghijklmnop:mem_1, qrstuvwxyz012345:mem_2"))
                    .hasSize(2);
        }

        @Test
        void readsNothingFromAnEmptyValue() {
            assertThat(Config.parseTokens(null)).isEmpty();
            assertThat(Config.parseTokens("  ")).isEmpty();
        }

        @Test
        void refusesAShortToken() {
            assertThatThrownBy(() -> Config.parseTokens("short:mem_1")).hasMessageContaining("too short or odd");
        }

        @Test
        void refusesAnEntryWithNoMember() {
            assertThatThrownBy(() -> Config.parseTokens("abcdefghijklmnop"))
                    .hasMessageContaining("is not token:member:scopes");
        }
    }

    @Nested
    class OpeningAStore {

        @Test
        void opensTheMemoryOne() {
            Ports store = Stores.openStore(defaults().withStore(StoreKind.MEMORY));
            store.members().save(Fixtures.aMember());
            assertThat(store.members().all()).hasSize(1);
            Stores.closeStore(store);
        }

        @Test
        void opensTheFileOneAndWritesToThePath() {
            Config config = onFile("store.jsonl");
            Ports store = Stores.openStore(config);
            store.members().save(Fixtures.aMember());
            Stores.closeStore(store);

            Ports reopened = Stores.openStore(config);
            assertThat(reopened.members().all()).hasSize(1);
            Stores.closeStore(reopened);
        }

        @Test
        void closesAStoreWithNothingToClose() {
            Ports store = Stores.openStore(defaults().withStore(StoreKind.MEMORY));
            assertThatCode(() -> Stores.closeStore(store)).doesNotThrowAnyException();
        }
    }

    @Nested
    class OpeningAStoreForReading {

        @Test
        void sharesAJournalAWriterAlreadyHolds() {
            Config config = onFile("store.jsonl");
            Ports writer = Stores.openStore(config);
            writer.members().save(Fixtures.aMember());

            Ports reader = Stores.openStoreForReading(config);
            assertThat(reader.members().all()).hasSize(1);
            Stores.closeStore(reader);
            Stores.closeStore(writer);
        }

        @Test
        void refusesASecondWriterOnTheSameJournal() {
            Config config = onFile("store.jsonl");
            Ports writer = Stores.openStore(config);
            assertThatThrownBy(() -> Stores.openStore(config)).isInstanceOf(ConflictException.class);
            Stores.closeStore(writer);
        }

        @Test
        void isTheSameThingForAMemoryStore() {
            Ports store = Stores.openStoreForReading(defaults().withStore(StoreKind.MEMORY));
            assertThat(store.members().all()).isEmpty();
            Stores.closeStore(store);
        }
    }

    @Nested
    class TheWriteLimit {

        @Test
        void hasADefaultACrewWillNotNotice() {
            Config config = defaults();
            assertThat(config.writesPerMinute()).isEqualTo(120);
            assertThat(config.burst()).isEqualTo(30);
        }

        @Test
        void takesBothFromTheEnvironment() {
            Config config = Config.read(Map.of("HITCHLINE_WRITES_PER_MINUTE", "600", "HITCHLINE_BURST", "90"));
            assertThat(config.writesPerMinute()).isEqualTo(600);
            assertThat(config.burst()).isEqualTo(90);
        }

        @Test
        void refusesAValueThatIsNotACount() {
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_BURST", "lots")))
                    .hasMessageContaining("whole number from 1 to 100000");
            assertThatThrownBy(() -> Config.read(Map.of("HITCHLINE_BURST", "0")))
                    .isInstanceOf(ValidationException.class);
        }
    }
}
