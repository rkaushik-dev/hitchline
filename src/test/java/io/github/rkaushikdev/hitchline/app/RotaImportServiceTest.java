package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.RotaImportService.RotaFailure;
import io.github.rkaushikdev.hitchline.app.RotaImportService.RotaOptions;
import io.github.rkaushikdev.hitchline.app.RotaImportService.RotaReport;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RotaImportServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String HEADER = "id,name,home,site,pattern,anchor,phase";
    private static final String ADA_LINE = ADA + ",Ada Vasquez,Europe/Lisbon,Europe/Oslo,14/14,2025-01-06,0";
    private static final String BO_LINE =
            "mem_FEDCBA9876543210,Bo Lindqvist,Europe/Stockholm,Europe/Oslo,14/14,2025-01-06,14";

    private MemoryStore store;
    private RotaImportService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-06-09T09:00:00Z"), IdSources.sequentialIds());
        service = new RotaImportService(store);
    }

    private static String file(String... lines) {
        return HEADER + "\n" + String.join("\n", lines);
    }

    private CrewMember ada() {
        return store.members().byId(ADA).orElseThrow();
    }

    @Nested
    class ACleanRota {

        @Test
        void addsEverybodyOnIt() {
            RotaReport report = service.rota(file(ADA_LINE, BO_LINE));
            assertThat(report).isEqualTo(new RotaReport(2, 2, 0, List.of()));
            assertThat(store.members().all()).hasSize(2);
        }

        @Test
        void readsThePatternAndThePhase() {
            service.rota(file(BO_LINE));
            CrewMember bo = store.members()
                    .byId(Ids.crewMemberId("mem_FEDCBA9876543210"))
                    .orElseThrow();
            assertThat(bo.rotation().pattern().format()).isEqualTo("14on 14off");
            assertThat(bo.rotation().phase()).isEqualTo(14);
        }

        @Test
        void updatesSomebodyAlreadyOnTheBooks() {
            store.members().save(Fixtures.aMember());
            RotaReport report = service.rota(file(ADA_LINE));
            assertThat(report.added()).isZero();
            assertThat(report.updated()).isEqualTo(1);
        }

        @Test
        void keepsTheDaySomebodyJoinedWhenItUpdatesThem() {
            store.members().save(Fixtures.aMember(o -> o.joinedOn("2023-05-01")));
            service.rota(file(ADA + ",Ada Vasquez,,Europe/Oslo,2-2-3,2025-06-02,0"));
            assertThat(ada().joinedOn().year()).isEqualTo(2023);
        }

        @Test
        void fallsBackToTheSiteZoneWhenHomeIsBlank() {
            service.rota(file(ADA + ",Ada Vasquez,,Europe/Oslo,14/14,2025-01-06,0"));
            assertThat(ada().homeZone()).isEqualTo("Europe/Oslo");
        }

        @Test
        void takesADefaultHomeZone() {
            service.rota(
                    file(ADA + ",Ada Vasquez,,Europe/Oslo,14/14,2025-01-06,0"),
                    new RotaOptions(false, Optional.of("Europe/London")));
            assertThat(ada().homeZone()).isEqualTo("Europe/London");
        }
    }

    @Nested
    class LinesThatWillNotGoIn {

        @Test
        void reportsAPatternItCannotReadAndKeepsTheRest() {
            String bad = "mem_1111111111111111,Cleo,Europe/London,Europe/Oslo,two weeks,2025-01-06,0";
            RotaReport report = service.rota(file(ADA_LINE, bad, BO_LINE));
            assertThat(report.added()).isEqualTo(2);
            assertThat(report.failures()).hasSize(1);
            RotaFailure failure = report.failures().get(0);
            assertThat(failure.line()).isEqualTo(3);
            assertThat(failure.name()).isEqualTo("Cleo");
        }

        @Test
        void reportsAPhaseThatIsNotANumber() {
            RotaReport report =
                    service.rota(file(ADA + ",Ada Vasquez,Europe/Lisbon,Europe/Oslo,14/14,2025-01-06,half"));
            assertThat(report.failures().get(0).message()).contains("whole number");
        }

        @Test
        void reportsAnIdThatIsNotAnId() {
            assertThat(service.rota(file("nobody,Ada Vasquez,Europe/Lisbon,Europe/Oslo,14/14,2025-01-06,0"))
                            .failures())
                    .hasSize(1);
        }

        @Test
        void reportsAZoneThePlatformDoesNotKnow() {
            assertThat(service.rota(file(ADA + ",Ada Vasquez,Europe/Lisbon,North Sea/Alpha,14/14,2025-01-06,0"))
                            .failures())
                    .hasSize(1);
        }
    }

    @Nested
    class ADryRun {

        @Test
        void checksEveryLineAndStoresNothing() {
            RotaReport report = service.rota(file(ADA_LINE, BO_LINE), new RotaOptions(true, Optional.empty()));
            assertThat(report.added()).isEqualTo(2);
            assertThat(store.members().all()).isEmpty();
        }
    }

    @Nested
    class TheFileItself {

        @Test
        void refusesOneMissingAColumn() {
            assertThatThrownBy(() -> service.rota("id,name\nmem_1,Ada\n")).hasMessageContaining("missing the columns");
        }

        @Test
        void readsAHeaderOnlyFileAsNothingToDo() {
            RotaReport report = service.rota(HEADER + "\n");
            assertThat(report.read()).isZero();
            assertThat(report.added()).isZero();
        }
    }

    @Nested
    class DescribingARun {

        @Test
        void readsBackInOneLine() {
            assertThat(new RotaReport(3, 2, 1, List.of()).describe())
                    .isEqualTo("3 read, 2 added, 1 updated, 0 refused");
        }
    }
}
