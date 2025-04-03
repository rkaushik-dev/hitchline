import { createHash } from 'node:crypto';
import { ValidationError } from '../errors.js';
import { formatInstant } from '../time/zone.js';
import { compareCheckIns, type CheckIn } from './checkin.js';

/**
 * A hash chain over the ledger. An operator being audited has to be able to
 * say that nothing was quietly edited after the fact, and a chain gives that
 * without a database or a signature: change one row and every digest after it
 * stops matching.
 */
export const GENESIS = '0'.repeat(64);

function fieldsOf(entry: CheckIn): string {
  return [
    entry.id,
    entry.habitId,
    entry.memberId,
    formatInstant(entry.at),
    formatInstant(entry.recordedAt),
    entry.reading === undefined ? '' : String(entry.reading),
    entry.note,
    entry.source,
    entry.idempotencyKey ?? '',
    entry.voidedAt === undefined ? '' : formatInstant(entry.voidedAt),
  ].join('');
}

export function digestOf(entry: CheckIn, previous: string): string {
  if (!/^[0-9a-f]{64}$/.test(previous)) {
    throw ValidationError.field('previous', 'must be a sha256 digest in lower case hex');
  }
  return createHash('sha256')
    .update(`${previous}${fieldsOf(entry)}`)
    .digest('hex');
}

export interface ChainLink {
  readonly entry: CheckIn;
  readonly digest: string;
}

/** Digests every entry in event order, each folded onto the one before it. */
export function chainOf(entries: readonly CheckIn[]): ChainLink[] {
  const ordered = [...entries].sort(compareCheckIns);
  const links: ChainLink[] = [];
  let previous = GENESIS;
  for (const entry of ordered) {
    previous = digestOf(entry, previous);
    links.push({ entry, digest: previous });
  }
  return links;
}

export function headOf(entries: readonly CheckIn[]): string {
  const links = chainOf(entries);
  return links[links.length - 1]?.digest ?? GENESIS;
}

export interface ChainCheck {
  readonly ok: boolean;
  readonly checked: number;
  /** The first entry whose digest did not match, when one did not. */
  readonly brokenAt: string | undefined;
}

/**
 * Recomputes the chain and compares it with digests recorded earlier. The
 * first mismatch is the interesting one: everything after it is a consequence.
 */
export function verifyChain(entries: readonly CheckIn[], recorded: readonly string[]): ChainCheck {
  const links = chainOf(entries);
  if (recorded.length !== links.length) {
    return { ok: false, checked: links.length, brokenAt: links[recorded.length]?.entry.id };
  }
  for (const [index, link] of links.entries()) {
    if (recorded[index] !== link.digest) {
      return { ok: false, checked: index + 1, brokenAt: link.entry.id };
    }
  }
  return { ok: true, checked: links.length, brokenAt: undefined };
}

export function describeCheck(check: ChainCheck): string {
  if (check.ok) return `${String(check.checked)} entries verified`;
  return `broke at ${check.brokenAt ?? 'the end'} after ${String(check.checked)} entries`;
}
