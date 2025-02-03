import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { ConflictError, ValidationError } from '../../domain/errors.js';
import { survivors, renumber } from './compact.js';
import { isRecordKind, type Journal, type JournalEntry, type RecordKind } from './journal.js';

/**
 * Backups. A backup is the compacted journal plus a header, which means it is
 * also a perfectly good journal: restoring is writing it back out.
 */
export const BACKUP_VERSION = 1;

export interface Backup {
  readonly version: number;
  readonly takenAt: string;
  readonly entries: readonly JournalEntry[];
}

export interface RestoreReport {
  readonly restored: number;
  readonly takenAt: string;
}

export async function takeBackup(journal: Journal, takenAt: string): Promise<Backup> {
  const report = await journal.read();
  return {
    version: BACKUP_VERSION,
    takenAt,
    entries: renumber(survivors(report.entries)),
  };
}

export async function writeBackup(path: string, backup: Backup): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, `${JSON.stringify(backup, null, 2)}\n`, 'utf8');
}

function checkEntry(value: unknown, index: number): JournalEntry {
  if (typeof value !== 'object' || value === null) {
    throw ValidationError.field('entries', `has a non object at position ${index}`);
  }
  const candidate = value as Partial<JournalEntry>;
  if (typeof candidate.kind !== 'string' || !isRecordKind(candidate.kind)) {
    throw ValidationError.field('entries', `has an unknown kind at position ${index}`);
  }
  if (candidate.op !== 'put' && candidate.op !== 'delete') {
    throw ValidationError.field('entries', `has an unknown operation at position ${index}`);
  }
  if (typeof candidate.seq !== 'number' || typeof candidate.at !== 'string') {
    throw ValidationError.field('entries', `is missing a header at position ${index}`);
  }
  if (typeof candidate.row !== 'object' || candidate.row === null) {
    throw ValidationError.field('entries', `has no row at position ${index}`);
  }
  return {
    seq: candidate.seq,
    at: candidate.at,
    kind: candidate.kind,
    op: candidate.op,
    row: candidate.row,
  };
}

export async function readBackup(path: string): Promise<Backup> {
  const text = await readFile(path, 'utf8');
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw ValidationError.field('backup', `at ${path} is not json`);
  }
  if (typeof parsed !== 'object' || parsed === null) {
    throw ValidationError.field('backup', `at ${path} is not an object`);
  }
  const candidate = parsed as Partial<Backup>;
  if (candidate.version !== BACKUP_VERSION) {
    throw ValidationError.field(
      'version',
      `is ${String(candidate.version)}, and this build reads ${BACKUP_VERSION}`,
    );
  }
  if (typeof candidate.takenAt !== 'string') {
    throw ValidationError.field('takenAt', 'is missing from the backup');
  }
  if (!Array.isArray(candidate.entries)) {
    throw ValidationError.field('entries', 'is missing from the backup');
  }
  return {
    version: candidate.version,
    takenAt: candidate.takenAt,
    entries: candidate.entries.map(checkEntry),
  };
}

export interface RestoreOptions {
  /** Needed to write over a journal that already holds something. */
  readonly force?: boolean;
}

export async function restore(
  journal: Journal,
  backup: Backup,
  options: RestoreOptions = {},
): Promise<RestoreReport> {
  const current = await journal.read();
  if (current.entries.length > 0 && options.force !== true) {
    throw new ConflictError(
      `${journal.path} already holds ${current.entries.length} entries, pass force to replace them`,
    );
  }
  await journal.rewrite(renumber([...backup.entries]));
  return { restored: backup.entries.length, takenAt: backup.takenAt };
}

export function backupCounts(backup: Backup): Partial<Record<RecordKind, number>> {
  const counts: Partial<Record<RecordKind, number>> = {};
  for (const entry of backup.entries) {
    counts[entry.kind] = (counts[entry.kind] ?? 0) + 1;
  }
  return counts;
}

export function describeBackup(backup: Backup): string {
  const counts = backupCounts(backup);
  const parts = Object.keys(counts)
    .sort()
    .map((kind) => `${kind} ${String(counts[kind as RecordKind])}`);
  return `${backup.entries.length} entries taken ${backup.takenAt}: ${parts.join(', ')}`;
}
