import type { Journal, JournalEntry, RecordKind } from './journal.js';
import type { Row } from './codec.js';

/**
 * Compaction. The journal only grows, and a habit edited weekly leaves a line
 * behind every time. Compacting keeps the last write for each record and drops
 * everything the store would not load anyway.
 */
export interface CompactionReport {
  readonly before: number;
  readonly after: number;
  readonly dropped: number;
  readonly skipped: number;
}

/** Kinds where a record has an identity and later writes replace earlier ones. */
const KEYED: Record<string, (row: Row) => string> = {
  member: (row) => String(row['id']),
  habit: (row) => String(row['id']),
  checkin: (row) => String(row['id']),
  crew: (row) => String(row['id']),
  sharing: (row) => String(row['memberId']),
  policy: (row) => String(row['memberId']),
  membership: (row) => `${String(row['crewId'])}|${String(row['memberId'])}`,
};

function keyFor(kind: RecordKind, row: Row): string | undefined {
  const reader = KEYED[kind];
  return reader === undefined ? undefined : reader(row);
}

/**
 * Walks the entries once and returns the ones worth keeping, in their original
 * order. Entries for kinds that are append only lists, such as overrides and
 * shields, are all kept.
 */
export function survivors(entries: readonly JournalEntry[]): JournalEntry[] {
  const lastSeqByKey = new Map<string, number>();
  const deleted = new Set<string>();

  for (const entry of entries) {
    const key = keyFor(entry.kind, entry.row);
    if (key === undefined) continue;
    const full = `${entry.kind}:${key}`;
    if (entry.op === 'delete') {
      deleted.add(full);
      lastSeqByKey.delete(full);
      continue;
    }
    deleted.delete(full);
    lastSeqByKey.set(full, entry.seq);
  }

  return entries.filter((entry) => {
    const key = keyFor(entry.kind, entry.row);
    if (key === undefined) return entry.op === 'put';
    const full = `${entry.kind}:${key}`;
    if (deleted.has(full)) return false;
    return lastSeqByKey.get(full) === entry.seq;
  });
}

/** Renumbers from one so a compacted file reads as if written in one go. */
export function renumber(entries: readonly JournalEntry[]): JournalEntry[] {
  return entries.map((entry, index) => ({ ...entry, seq: index + 1 }));
}

export async function compact(journal: Journal): Promise<CompactionReport> {
  const report = await journal.read();
  const kept = renumber(survivors(report.entries));
  await journal.rewrite(kept);
  return {
    before: report.entries.length,
    after: kept.length,
    dropped: report.entries.length - kept.length,
    skipped: report.skipped,
  };
}

/** True when a journal has grown enough that compacting is worth the write. */
export function worthCompacting(report: CompactionReport, ratio = 0.3): boolean {
  if (report.before === 0) return false;
  return report.dropped / report.before >= ratio;
}

export async function estimate(journal: Journal): Promise<CompactionReport> {
  const report = await journal.read();
  const kept = survivors(report.entries);
  return {
    before: report.entries.length,
    after: kept.length,
    dropped: report.entries.length - kept.length,
    skipped: report.skipped,
  };
}
