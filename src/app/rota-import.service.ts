import { isDomainError, ValidationError } from '../domain/errors.js';
import { crewMember, type CrewMember } from '../domain/crew/member.js';
import { crewMemberId } from '../domain/ids.js';
import { rotationAssignment } from '../domain/rotation/assignment.js';
import { parseRotationPattern } from '../domain/rotation/pattern.js';
import { parseCivilDate } from '../domain/time/civil.js';
import { civilDateIn } from '../domain/time/zone.js';
import { readTable, requireColumns } from '../import/csv.js';
import type { Ports } from './ports.js';

/**
 * The rota arrives as a spreadsheet. It always does: a crewing office keeps it
 * in one, and the first thing anybody wants from a new tool is to stop
 * retyping it. One line per person, and a line that will not go in is reported
 * rather than stopping the rest.
 */
export interface RotaFailure {
  readonly line: number;
  readonly name: string;
  readonly message: string;
}

export interface RotaReport {
  readonly read: number;
  readonly added: number;
  readonly updated: number;
  readonly failures: readonly RotaFailure[];
}

export interface RotaOptions {
  readonly dryRun?: boolean;
  /** Used when a line leaves the home zone blank. */
  readonly defaultHomeZone?: string;
}

export interface RotaImportService {
  rota(text: string, options?: RotaOptions): Promise<RotaReport>;
}

const REQUIRED = ['id', 'name', 'pattern', 'anchor'];

/** A blank cell means the column was left empty, not that it held an empty value. */
function cell(row: Record<string, string>, key: string): string | undefined {
  const value = (row[key] ?? '').trim();
  return value.length === 0 ? undefined : value;
}

export function rotaImportService(ports: Ports): RotaImportService {
  return {
    async rota(text: string, options: RotaOptions = {}): Promise<RotaReport> {
      const table = readTable(text);
      requireColumns(table, REQUIRED);

      let added = 0;
      let updated = 0;
      const failures: RotaFailure[] = [];

      for (const [index, row] of table.rows.entries()) {
        const line = index + 2;
        const name = (row['name'] ?? '').trim();
        try {
          const id = crewMemberId(cell(row, 'id') ?? '');
          const existing = await ports.members.byId(id);
          const site = cell(row, 'site') ?? existing?.siteZone ?? 'UTC';
          const home = cell(row, 'home') ?? options.defaultHomeZone ?? site;
          const phase = cell(row, 'phase') ?? '0';
          if (!/^\d+$/.test(phase)) {
            throw ValidationError.field('phase', `must be a whole number, got ${phase}`);
          }

          const member: CrewMember = crewMember({
            id,
            displayName: name,
            homeZone: home,
            siteZone: site,
            joinedOn: existing?.joinedOn ?? civilDateIn(site, ports.clock.now()),
            status: existing?.status,
            rotation: rotationAssignment({
              pattern: parseRotationPattern(cell(row, 'pattern') ?? ''),
              anchor: parseCivilDate(cell(row, 'anchor') ?? ''),
              phase: Number(phase),
            }),
          });

          if (options.dryRun !== true) {
            await ports.members.save(member);
          }
          if (existing === undefined) added += 1;
          else updated += 1;
        } catch (error) {
          if (!isDomainError(error)) throw error;
          failures.push({ line, name, message: error.message });
        }
      }

      return { read: table.rows.length, added, updated, failures };
    },
  };
}

export function describeRota(report: RotaReport): string {
  return [
    `${String(report.read)} read`,
    `${String(report.added)} added`,
    `${String(report.updated)} updated`,
    `${String(report.failures.length)} refused`,
  ].join(', ');
}
