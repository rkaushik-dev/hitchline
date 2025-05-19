import { mkdir, open, readFile, rm } from 'node:fs/promises';
import { dirname } from 'node:path';
import { ConflictError, ValidationError } from '../../domain/errors.js';

/**
 * An advisory lock beside the journal. Two writers on one file is the failure
 * this codebase is least able to detect after the fact: both would append and
 * the later reader would see a plausible, wrong history. Exclusive create is
 * the one filesystem primitive that behaves the same on every platform this
 * runs on.
 */
export interface LockHolder {
  readonly path: string;
  readonly heldBy: LockOwner;
  release(): Promise<void>;
}

export interface LockOwner {
  readonly pid: number;
  readonly host: string;
  readonly at: string;
}

export interface LockOptions {
  readonly owner: LockOwner;
  /** Break a lock whose holder is this many milliseconds old. */
  readonly staleAfterMs?: number;
  readonly nowMs?: number;
}

const DEFAULT_STALE_MS = 15 * 60_000;

export function lockPathFor(target: string): string {
  if (target.trim().length === 0) {
    throw ValidationError.field('path', 'must not be empty');
  }
  return `${target}.lock`;
}

function readOwner(text: string): LockOwner | undefined {
  try {
    const parsed: unknown = JSON.parse(text);
    if (typeof parsed !== 'object' || parsed === null) return undefined;
    const candidate = parsed as Partial<LockOwner>;
    if (typeof candidate.pid !== 'number' || typeof candidate.host !== 'string') return undefined;
    if (typeof candidate.at !== 'string') return undefined;
    return { pid: candidate.pid, host: candidate.host, at: candidate.at };
  } catch {
    return undefined;
  }
}

export async function readLock(target: string): Promise<LockOwner | undefined> {
  try {
    return readOwner(await readFile(lockPathFor(target), 'utf8'));
  } catch (cause) {
    if ((cause as NodeJS.ErrnoException).code === 'ENOENT') return undefined;
    throw cause;
  }
}

/**
 * Takes the lock, or says who holds it. A lock whose stamp is older than the
 * stale window is taken over, because a process killed with the lock held
 * would otherwise shut the store for good.
 */
export async function takeLock(target: string, options: LockOptions): Promise<LockHolder> {
  const path = lockPathFor(target);
  const staleAfterMs = options.staleAfterMs ?? DEFAULT_STALE_MS;
  const nowMs = options.nowMs ?? Date.now();
  await mkdir(dirname(path), { recursive: true });

  const write = async (): Promise<void> => {
    const handle = await open(path, 'wx');
    try {
      await handle.write(JSON.stringify(options.owner), null, 'utf8');
    } finally {
      await handle.close();
    }
  };

  try {
    await write();
  } catch (cause) {
    if ((cause as NodeJS.ErrnoException).code !== 'EEXIST') throw cause;
    const held = await readLock(target);
    const heldAtMs = held === undefined ? 0 : Date.parse(held.at);
    const age = Number.isNaN(heldAtMs) ? Number.POSITIVE_INFINITY : nowMs - heldAtMs;
    if (held !== undefined && age < staleAfterMs) {
      throw new ConflictError(
        `${path} is held by pid ${String(held.pid)} on ${held.host} since ${held.at}`,
      );
    }
    await rm(path, { force: true });
    await write();
  }

  return {
    path,
    heldBy: options.owner,
    release: async () => {
      await rm(path, { force: true });
    },
  };
}

export function ownerHere(nowIso: string): LockOwner {
  return { pid: process.pid, host: process.env['HOSTNAME'] ?? 'local', at: nowIso };
}
