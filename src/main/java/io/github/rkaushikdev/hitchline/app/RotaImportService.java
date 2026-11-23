package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.MemberStatus;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.imports.CsvReader;
import io.github.rkaushikdev.hitchline.imports.CsvReader.CsvTable;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rota as a spreadsheet: one line per person with {@code id}, {@code name} and {@code pattern}
 * columns, and either an {@code anchor} with an optional {@code phase} or the {@code crew_change}
 * day somebody next flies out, plus optionally {@code site} and {@code home}. A crewing office
 * keeps it in one, and the first thing anybody wants from a new tool is to stop retyping it. A
 * line that will not go in is reported rather than stopping the rest; a line with a crew change
 * day is pinned on that and its anchor and phase are not read.
 */
public final class RotaImportService {

    private static final List<String> REQUIRED = List.of("id", "name", "pattern");
    private static final List<String> PINNED_BY = List.of("anchor", "crew_change");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final Ports ports;

    public RotaImportService(Ports ports) {
        this.ports = ports;
    }

    /**
     * A line that did not go in.
     *
     * @param line the line of the file, the header being line 1
     * @param name the name on the line
     * @param message why
     */
    public record RotaFailure(int line, String name, String message) {}

    /**
     * What an import did.
     *
     * @param read lines read
     * @param added members new to the store
     * @param updated members already there
     * @param failures lines refused
     */
    public record RotaReport(int read, int added, int updated, List<RotaFailure> failures) {

        public RotaReport {
            failures = List.copyOf(failures);
        }

        /** One line, such as {@code 3 read, 2 added, 0 updated, 1 refused}. */
        public String describe() {
            return read + " read, " + added + " added, " + updated + " updated, " + failures.size() + " refused";
        }
    }

    /**
     * How to import.
     *
     * @param dryRun check every line without storing anything
     * @param defaultHomeZone used when a line leaves the home zone blank
     */
    public record RotaOptions(boolean dryRun, Optional<String> defaultHomeZone) {

        public static final RotaOptions STORE = new RotaOptions(false, Optional.empty());
    }

    /** A blank cell means the column was left empty, not that it held an empty value. */
    private static Optional<String> cell(Map<String, String> row, String key) {
        String value = Texts.trim(row.getOrDefault(key, ""));
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    public RotaReport rota(String text) {
        return rota(text, RotaOptions.STORE);
    }

    public RotaReport rota(String text, RotaOptions options) {
        CsvTable table = CsvReader.readTable(text);
        table.requireColumns(REQUIRED);
        if (PINNED_BY.stream().noneMatch(table.headers()::contains)) {
            throw ValidationException.field("csv", "needs an anchor or a crew_change column");
        }
        int added = 0;
        int updated = 0;
        List<RotaFailure> failures = new ArrayList<>();
        for (int index = 0; index < table.rows().size(); index += 1) {
            Map<String, String> row = table.rows().get(index);
            int line = index + 2;
            String name = Texts.trim(row.getOrDefault("name", ""));
            try {
                String id = Ids.crewMemberId(cell(row, "id").orElse(""));
                Optional<CrewMember> existing = ports.members().byId(id);
                String site = cell(row, "site")
                        .orElseGet(() -> existing.map(CrewMember::siteZone).orElse("UTC"));
                String home = cell(row, "home").or(options::defaultHomeZone).orElse(site);
                CivilDate joinedOn = existing.map(CrewMember::joinedOn)
                        .orElseGet(() -> Zones.civilDateIn(site, ports.clock().now()));
                MemberStatus status = existing.map(CrewMember::status).orElse(MemberStatus.ACTIVE);
                RotationAssignment rotation = rotationOf(row);
                CrewMember member = new CrewMember(id, name, home, site, rotation, status, joinedOn);
                if (!options.dryRun()) {
                    ports.members().save(member);
                }
                if (existing.isEmpty()) {
                    added += 1;
                } else {
                    updated += 1;
                }
            } catch (DomainException refused) {
                failures.add(new RotaFailure(line, name, refused.getMessage()));
            }
        }
        return new RotaReport(table.rows().size(), added, updated, failures);
    }

    private static RotationAssignment rotationOf(Map<String, String> row) {
        RotationPattern pattern = RotationPattern.parse(cell(row, "pattern").orElse(""));
        Optional<String> crewChange = cell(row, "crew_change");
        if (crewChange.isPresent()) {
            return RotationAssignment.fromCrewChange(pattern, CivilDate.parse(crewChange.get()));
        }
        String phase = cell(row, "phase").orElse("0");
        if (!DIGITS.matcher(phase).matches()) {
            throw ValidationException.field("phase", "must be a whole number, got " + phase);
        }
        return RotationAssignment.of(
                pattern, CivilDate.parse(cell(row, "anchor").orElse("")), phaseOf(phase));
    }

    /** A run of digits as a phase; one too long for a phase reads as one past every cycle. */
    private static int phaseOf(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLong) {
            return Integer.MAX_VALUE;
        }
    }
}
