import { ValidationError } from './errors.js';

declare const brand: unique symbol;

export type Branded<T, B extends string> = T & { readonly [brand]: B };

export type CrewMemberId = Branded<string, 'CrewMemberId'>;
export type CrewId = Branded<string, 'CrewId'>;
export type HabitId = Branded<string, 'HabitId'>;
export type CheckInId = Branded<string, 'CheckInId'>;

const PREFIXES = {
  CrewMemberId: 'mem',
  CrewId: 'crw',
  HabitId: 'hab',
  CheckInId: 'chk',
} as const;

export type IdKind = keyof typeof PREFIXES;

/** Crockford base32 without I, L, O and U so ids survive being read aloud. */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const SUFFIX_LENGTH = 16;
const SUFFIX_PATTERN = new RegExp(`^[${ALPHABET}]{${SUFFIX_LENGTH}}$`);

export function idPrefix(kind: IdKind): string {
  return PREFIXES[kind];
}

function parse<K extends IdKind>(kind: K, raw: string): string {
  const prefix = PREFIXES[kind];
  const separator = raw.indexOf('_');
  if (separator < 0) {
    throw ValidationError.field(kind, `must look like ${prefix}_XXXXXXXXXXXXXXXX`);
  }
  const head = raw.slice(0, separator);
  const tail = raw.slice(separator + 1);
  if (head !== prefix) {
    throw ValidationError.field(kind, `must start with ${prefix}_ but starts with ${head}_`);
  }
  if (!SUFFIX_PATTERN.test(tail)) {
    throw ValidationError.field(kind, `has an unreadable suffix ${tail}`);
  }
  return raw;
}

export function crewMemberId(raw: string): CrewMemberId {
  return parse('CrewMemberId', raw) as CrewMemberId;
}

export function crewId(raw: string): CrewId {
  return parse('CrewId', raw) as CrewId;
}

export function habitId(raw: string): HabitId {
  return parse('HabitId', raw) as HabitId;
}

export function checkInId(raw: string): CheckInId {
  return parse('CheckInId', raw) as CheckInId;
}

export function isWellFormed(kind: IdKind, raw: string): boolean {
  try {
    parse(kind, raw);
    return true;
  } catch {
    return false;
  }
}

/**
 * Builds an id from bytes supplied by the caller. The domain never reaches for
 * a random source of its own, so tests can hand it a fixed sequence.
 */
export function mintId(kind: IdKind, bytes: Uint8Array): string {
  if (bytes.length < SUFFIX_LENGTH) {
    throw ValidationError.field('bytes', `needs at least ${SUFFIX_LENGTH} entries`);
  }
  let suffix = '';
  for (let index = 0; index < SUFFIX_LENGTH; index += 1) {
    const byte = bytes[index] ?? 0;
    suffix += ALPHABET[byte % ALPHABET.length];
  }
  return `${PREFIXES[kind]}_${suffix}`;
}
