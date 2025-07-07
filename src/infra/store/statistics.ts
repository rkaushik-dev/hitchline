import { readdir, rm, stat } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { ValidationError } from '../../domain/errors.js';
import type { JournalEntry, RecordKind } from './journal.js';

/**
 * What is in the journal, and what to do about the pile of backups beside it.
 * Both are questions somebody asks once a quarter and neither is worth a
 * dashboard, so they answer in one line each.
 */
export interface StoreStatistics {
  readonly entries: number;
  readonly byKind: Partial<Record<RecordKind, number>>;
  readonly firstAt: string | undefined;
  readonly lastAt: string | undefined;
  readonly writers: number;
}

export function statisticsOf(entries: readonly JournalEntry[]): StoreStatistics {
  const byKind: Partial<Record<RecordKind, number>> = {};
  const days = new Set<string>();
  for (const entry of entries) {
    byKind[entry.kind] = (byKind[entry.kind] ?? 0) + 1;
    days.add(entry.at.slice(0, 10));
  }
  const sorted = [...entries].sort((left, right) => left.at.localeCompare(right.at));
  return {
    entries: entries.length,
    byKind,
    firstAt: sorted[0]?.at,
    lastAt: sorted[sorted.length - 1]?.at,
    writers: days.size,
  };
}

export function describeStatistics(stats: StoreStatistics): string {
  const kinds = Object.keys(stats.byKind)
    .sort()
    .map((kind) => `${kind} ${String(stats.byKind[kind as RecordKind])}`)
    .join(', ');
  return `${String(stats.entries)} entries over ${String(stats.writers)} days: ${kinds}`;
}

export interface PruneOptions {
  /** How many backups to keep, newest first. */
  readonly keep: number;
  readonly dryRun?: boolean;
}

export interface PruneReport {
  readonly kept: readonly string[];
  readonly removed: readonly string[];
}

const BACKUP_PATTERN = /\.json$/;

/**
 * Keeps the newest few backups and removes the rest. Newest is by the name,
 * not by the file time: a restore copies files about and the times move, and
 * the names are stamped when the backup was taken.
 */
export async function pruneBackups(directory: string, options: PruneOptions): Promise<PruneReport> {
  if (!Number.isInteger(options.keep) || options.keep < 1) {
    throw ValidationError.field('keep', `must be at least one, got ${options.keep}`);
  }
  const names = (await readdir(directory))
    .filter((name) => BACKUP_PATTERN.test(name))
    .sort()
    .reverse();

  const kept = names.slice(0, options.keep);
  const removed = names.slice(options.keep);
  if (options.dryRun !== true) {
    for (const name of removed) {
      await rm(join(directory, name), { force: true });
    }
  }
  return { kept, removed };
}

export async function backupSizes(paths: readonly string[]): Promise<Record<string, number>> {
  const sizes: Record<string, number> = {};
  for (const path of paths) {
    const info = await stat(path);
    sizes[basename(path)] = info.size;
  }
  return sizes;
}

export function backupDirectoryFor(storePath: string): string {
  return join(dirname(storePath), 'backups');
}
