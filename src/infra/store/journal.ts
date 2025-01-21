import { open, mkdir, readFile, rename, writeFile, type FileHandle } from 'node:fs/promises';
import { dirname } from 'node:path';
import { ValidationError } from '../../domain/errors.js';
import type { Row } from './codec.js';

/**
 * An append only journal, one json object per line. Every change to the store
 * is a line here; the state is whatever you get by replaying them in order.
 * There is no database because there is no database available on the boxes
 * this runs on, and a file that only ever grows is easy to reason about.
 */
export type RecordKind =
  | 'member'
  | 'habit'
  | 'checkin'
  | 'override'
  | 'shield'
  | 'crew'
  | 'membership'
  | 'sharing'
  | 'policy';

export type Operation = 'put' | 'delete';

export interface JournalEntry {
  readonly seq: number;
  readonly at: string;
  readonly kind: RecordKind;
  readonly op: Operation;
  readonly row: Row;
}

export interface ReadReport {
  readonly entries: readonly JournalEntry[];
  /** Lines that could not be parsed, kept as a count rather than thrown away. */
  readonly skipped: number;
  readonly lastSeq: number;
}

const KINDS: readonly RecordKind[] = [
  'member',
  'habit',
  'checkin',
  'override',
  'shield',
  'crew',
  'membership',
  'sharing',
  'policy',
];

export function isRecordKind(value: string): value is RecordKind {
  return (KINDS as readonly string[]).includes(value);
}

function parseLine(line: string): JournalEntry | undefined {
  const trimmed = line.trim();
  if (trimmed.length === 0) return undefined;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return undefined;
  }
  if (typeof parsed !== 'object' || parsed === null) return undefined;
  const candidate = parsed as Partial<JournalEntry>;
  if (typeof candidate.seq !== 'number' || typeof candidate.at !== 'string') return undefined;
  if (typeof candidate.kind !== 'string' || !isRecordKind(candidate.kind)) return undefined;
  if (candidate.op !== 'put' && candidate.op !== 'delete') return undefined;
  if (typeof candidate.row !== 'object' || candidate.row === null) return undefined;
  return {
    seq: candidate.seq,
    at: candidate.at,
    kind: candidate.kind,
    op: candidate.op,
    row: candidate.row,
  };
}

export async function readJournal(path: string): Promise<ReadReport> {
  let text: string;
  try {
    text = await readFile(path, 'utf8');
  } catch (cause) {
    if ((cause as NodeJS.ErrnoException).code === 'ENOENT') {
      return { entries: [], skipped: 0, lastSeq: 0 };
    }
    throw cause;
  }
  const entries: JournalEntry[] = [];
  let skipped = 0;
  for (const line of text.split('\n')) {
    if (line.trim().length === 0) continue;
    const entry = parseLine(line);
    if (entry === undefined) {
      skipped += 1;
      continue;
    }
    entries.push(entry);
  }
  const lastSeq = entries.reduce((highest, entry) => Math.max(highest, entry.seq), 0);
  return { entries, skipped, lastSeq };
}

export interface Journal {
  append(kind: RecordKind, op: Operation, row: Row, at: string): Promise<JournalEntry>;
  read(): Promise<ReadReport>;
  /** Rewrites the file from a fresh set of entries, used by compaction. */
  rewrite(entries: readonly JournalEntry[]): Promise<void>;
  flush(): Promise<void>;
  close(): Promise<void>;
  readonly path: string;
}

export async function openJournal(path: string): Promise<Journal> {
  if (path.trim().length === 0) {
    throw ValidationError.field('path', 'must not be empty');
  }
  await mkdir(dirname(path), { recursive: true });
  const report = await readJournal(path);
  let seq = report.lastSeq;
  let handle: FileHandle | undefined = await open(path, 'a');

  async function writer(): Promise<FileHandle> {
    if (handle === undefined) {
      handle = await open(path, 'a');
    }
    return handle;
  }

  return {
    path,

    async append(kind: RecordKind, op: Operation, row: Row, at: string): Promise<JournalEntry> {
      seq += 1;
      const entry: JournalEntry = { seq, at, kind, op, row };
      const file = await writer();
      await file.write(`${JSON.stringify(entry)}\n`, null, 'utf8');
      return entry;
    },

    read: () => readJournal(path),

    async rewrite(entries: readonly JournalEntry[]): Promise<void> {
      const temporary = `${path}.rewrite`;
      const body = entries.map((entry) => JSON.stringify(entry)).join('\n');
      await writeFile(temporary, entries.length === 0 ? '' : `${body}\n`, 'utf8');
      if (handle !== undefined) {
        await handle.close();
        handle = undefined;
      }
      await rename(temporary, path);
      seq = entries.reduce((highest, entry) => Math.max(highest, entry.seq), 0);
    },

    async flush(): Promise<void> {
      if (handle !== undefined) await handle.sync();
    },

    async close(): Promise<void> {
      if (handle === undefined) return;
      const closing = handle;
      handle = undefined;
      await closing.close();
    },
  };
}

/** The last entry for each key, which is the state the store loads. */
export function fold(
  entries: readonly JournalEntry[],
  kind: RecordKind,
  keyOf: (row: Row) => string,
): Map<string, Row> {
  const state = new Map<string, Row>();
  for (const entry of entries) {
    if (entry.kind !== kind) continue;
    const key = keyOf(entry.row);
    if (entry.op === 'delete') {
      state.delete(key);
      continue;
    }
    state.set(key, entry.row);
  }
  return state;
}

/** Every live entry of a kind, for the tables that are lists rather than maps. */
export function collect(entries: readonly JournalEntry[], kind: RecordKind): Row[] {
  return entries.filter((entry) => entry.kind === kind && entry.op === 'put').map((e) => e.row);
}
