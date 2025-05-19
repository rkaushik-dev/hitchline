import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import {
  lockPathFor,
  ownerHere,
  readLock,
  takeLock,
  type LockOwner,
} from '../../../../src/infra/store/lock.js';
import { ConflictError, ValidationError } from '../../../../src/domain/errors.js';

let directory: string;
let target: string;

const owner: LockOwner = { pid: 4242, host: 'grane-b', at: '2025-05-19T09:00:00.000Z' };
const nowMs = Date.parse('2025-05-19T09:00:30.000Z');

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-lock-'));
  target = join(directory, 'nested', 'hitchline.jsonl');
});

afterEach(async () => {
  await rm(directory, { recursive: true, force: true });
});

describe('the lock path', () => {
  it('sits beside the file it guards', () => {
    expect(lockPathFor('/data/store.jsonl')).toBe('/data/store.jsonl.lock');
  });

  it('refuses an empty target', () => {
    expect(() => lockPathFor('  ')).toThrow(ValidationError);
  });
});

describe('taking it', () => {
  it('writes a lock naming the holder', async () => {
    const held = await takeLock(target, { owner, nowMs });
    expect(await readLock(target)).toEqual(owner);
    expect(held.path.endsWith('.lock')).toBe(true);
    await held.release();
  });

  it('creates the directory it needs', async () => {
    const held = await takeLock(target, { owner, nowMs });
    expect(await readFile(held.path, 'utf8')).toContain('grane-b');
    await held.release();
  });

  it('refuses when somebody else holds it', async () => {
    const held = await takeLock(target, { owner, nowMs });
    await expect(takeLock(target, { owner: { ...owner, pid: 99 }, nowMs })).rejects.toThrow(
      ConflictError,
    );
    await held.release();
  });

  it('names the holder in the refusal', async () => {
    const held = await takeLock(target, { owner, nowMs });
    await expect(takeLock(target, { owner, nowMs })).rejects.toThrow(/pid 4242 on grane-b/);
    await held.release();
  });

  it('can be taken again once released', async () => {
    const held = await takeLock(target, { owner, nowMs });
    await held.release();
    const second = await takeLock(target, { owner, nowMs });
    expect(second.heldBy.pid).toBe(4242);
    await second.release();
  });

  it('reads no lock when there is none', async () => {
    expect(await readLock(target)).toBeUndefined();
  });
});

describe('a stale lock', () => {
  it('is taken over once it is old enough', async () => {
    await takeLock(target, { owner, nowMs });
    const later = Date.parse('2025-05-19T09:30:00.000Z');
    const second = await takeLock(target, {
      owner: { ...owner, pid: 99, at: '2025-05-19T09:30:00.000Z' },
      nowMs: later,
    });
    expect((await readLock(target))?.pid).toBe(99);
    await second.release();
  });

  it('is left alone inside the stale window', async () => {
    const held = await takeLock(target, { owner, nowMs, staleAfterMs: 60 * 60_000 });
    const later = Date.parse('2025-05-19T09:30:00.000Z');
    await expect(
      takeLock(target, { owner, nowMs: later, staleAfterMs: 60 * 60_000 }),
    ).rejects.toThrow(ConflictError);
    await held.release();
  });

  it('takes over a lock file nobody can read', async () => {
    await mkdir(dirname(target), { recursive: true });
    await writeFile(lockPathFor(target), 'not json at all', 'utf8');
    const held = await takeLock(target, { owner, nowMs });
    expect((await readLock(target))?.pid).toBe(4242);
    await held.release();
  });
});

describe('the owner here', () => {
  it('names this process', () => {
    const here = ownerHere('2025-05-19T09:00:00.000Z');
    expect(here.pid).toBe(process.pid);
    expect(here.at).toBe('2025-05-19T09:00:00.000Z');
    expect(here.host.length).toBeGreaterThan(0);
  });
});
