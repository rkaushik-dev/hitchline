import { isDomainError, ValidationError } from '../domain/errors.js';
import { habitId as parseHabitId } from '../domain/ids.js';
import { readTable, requireColumns } from '../import/csv.js';
import { checkInsService } from './checkins.service.js';
import type { Ports } from './ports.js';

/**
 * Bulk import. A rig that ran on paper for a fortnight hands over a
 * spreadsheet, and one bad line in it must not throw the other two hundred
 * away, so every row is reported on its own and the file is never atomic.
 */
export interface ImportFailure {
  readonly line: number;
  readonly message: string;
}

export interface ImportReport {
  readonly read: number;
  readonly accepted: number;
  readonly duplicates: number;
  readonly failures: readonly ImportFailure[];
}

export interface ImportOptions {
  /** Read and check every row without storing anything. */
  readonly dryRun?: boolean;
  readonly source?: 'import' | 'supervisor';
}

export interface ImportService {
  checkIns(text: string, options?: ImportOptions): Promise<ImportReport>;
}

const REQUIRED = ['habit_id', 'at'];

export function importService(ports: Ports): ImportService {
  const checkIns = checkInsService(ports);

  return {
    async checkIns(text: string, options: ImportOptions = {}): Promise<ImportReport> {
      const table = readTable(text);
      requireColumns(table, REQUIRED);

      let accepted = 0;
      let duplicates = 0;
      const failures: ImportFailure[] = [];

      for (const [index, row] of table.rows.entries()) {
        const line = index + 2;
        try {
          const reading = (row['reading'] ?? '').trim();
          const key = (row['idempotency_key'] ?? '').trim();
          if (reading.length > 0 && Number.isNaN(Number(reading))) {
            throw ValidationError.field('reading', `is not a number: ${reading}`);
          }
          const command = {
            habitId: parseHabitId(row['habit_id'] ?? ''),
            at: row['at'] ?? '',
            reading: reading.length === 0 ? undefined : Number(reading),
            note: (row['note'] ?? '').trim() || undefined,
            source: options.source ?? ('import' as const),
            idempotencyKey: key.length === 0 ? undefined : key,
          };
          if (options.dryRun === true) {
            await checkDryRun(ports, command.habitId);
            accepted += 1;
            continue;
          }
          const result = await checkIns.record(command);
          if (result.accepted) accepted += 1;
          else duplicates += 1;
        } catch (error) {
          if (!isDomainError(error)) throw error;
          failures.push({ line, message: error.message });
        }
      }

      return { read: table.rows.length, accepted, duplicates, failures };
    },
  };
}

async function checkDryRun(ports: Ports, habitId: ReturnType<typeof parseHabitId>): Promise<void> {
  const habit = await ports.habits.byId(habitId);
  if (habit === undefined) {
    throw ValidationError.field('habit_id', `names a habit that does not exist: ${habitId}`);
  }
}

export function describeImport(report: ImportReport): string {
  const parts = [
    `${String(report.read)} read`,
    `${String(report.accepted)} accepted`,
    `${String(report.duplicates)} already there`,
    `${String(report.failures.length)} refused`,
  ];
  return parts.join(', ');
}
