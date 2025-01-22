import { ValidationError } from '../../domain/errors.js';
import { checkIn, type CheckIn, type CheckInSource } from '../../domain/checkin/checkin.js';
import {
  crew,
  membership,
  type Crew,
  type CrewRole,
  type Membership,
} from '../../domain/crew/crew.js';
import { crewMember, type CrewMember, type MemberStatus } from '../../domain/crew/member.js';
import {
  sharingPreference,
  type SharingPreference,
  type Visibility,
} from '../../domain/crew/sharing.js';
import { habit, type Habit } from '../../domain/habit/habit.js';
import { nudgePolicy, type NudgeChannel, type NudgePolicy } from '../../domain/nudge/policy.js';
import { parseCadence, formatCadence } from '../../domain/habit/cadence.js';
import { formatTarget, parseTarget } from '../../domain/habit/target.js';
import {
  checkInId,
  crewId,
  crewMemberId,
  habitId,
  type CrewMemberId,
  type HabitId,
} from '../../domain/ids.js';
import {
  rosterOverride,
  type OverrideReason,
  type RosterOverride,
} from '../../domain/rotation/override.js';
import { rotationAssignment } from '../../domain/rotation/assignment.js';
import {
  formatRotationPattern,
  parseRotationPattern,
  type DayRole,
} from '../../domain/rotation/pattern.js';
import { shield, type Shield, type ShieldReason } from '../../domain/adherence/shield.js';
import { formatClockRange } from '../../domain/time/clocktime.js';
import { formatCivilDate, parseCivilDate } from '../../domain/time/civil.js';
import { formatDateRange, parseDateRange } from '../../domain/time/range.js';
import { formatInstant, parseInstant } from '../../domain/time/zone.js';

/**
 * Records on their way to and from disk. Decoding goes back through the domain
 * constructors rather than casting, so a row that was hand edited into
 * something impossible is rejected at load rather than at some later report.
 */
export type Row = Record<string, unknown>;

function text(row: Row, key: string): string {
  const value = row[key];
  if (typeof value !== 'string') {
    throw ValidationError.field(key, `must be a string in a stored row, got ${typeof value}`);
  }
  return value;
}

function optionalText(row: Row, key: string): string | undefined {
  const value = row[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'string') {
    throw ValidationError.field(key, `must be a string when present, got ${typeof value}`);
  }
  return value;
}

function count(row: Row, key: string): number {
  const value = row[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw ValidationError.field(key, 'must be a number in a stored row');
  }
  return value;
}

function optionalCount(row: Row, key: string): number | undefined {
  const value = row[key];
  if (value === undefined || value === null) return undefined;
  return count(row, key);
}

function words(row: Row, key: string): string[] {
  const value = row[key];
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== 'string')) {
    throw ValidationError.field(key, 'must be a list of strings');
  }
  return value as string[];
}

export function encodeMember(member: CrewMember): Row {
  return {
    id: member.id,
    displayName: member.displayName,
    homeZone: member.homeZone,
    siteZone: member.siteZone,
    pattern: formatRotationPattern(member.rotation.pattern),
    anchor: formatCivilDate(member.rotation.anchor),
    phase: member.rotation.phase,
    status: member.status,
    joinedOn: formatCivilDate(member.joinedOn),
  };
}

export function decodeMember(row: Row): CrewMember {
  return crewMember({
    id: crewMemberId(text(row, 'id')),
    displayName: text(row, 'displayName'),
    homeZone: text(row, 'homeZone'),
    siteZone: text(row, 'siteZone'),
    rotation: rotationAssignment({
      pattern: parseRotationPattern(text(row, 'pattern')),
      anchor: parseCivilDate(text(row, 'anchor')),
      phase: count(row, 'phase'),
    }),
    status: text(row, 'status') as MemberStatus,
    joinedOn: parseCivilDate(text(row, 'joinedOn')),
  });
}

export function encodeHabit(entry: Habit): Row {
  return {
    id: entry.id,
    memberId: entry.memberId,
    name: entry.name,
    cadence: formatCadence(entry.cadence),
    target: entry.target === undefined ? null : formatTarget(entry.target),
    status: entry.status,
    startsOn: formatCivilDate(entry.startsOn),
    endsOn: entry.endsOn === undefined ? null : formatCivilDate(entry.endsOn),
    tags: [...entry.tags],
    graceDays: entry.graceDays,
    createdAt: formatInstant(entry.createdAt),
  };
}

export function decodeHabit(row: Row): Habit {
  const target = optionalText(row, 'target');
  const endsOn = optionalText(row, 'endsOn');
  return habit({
    id: habitId(text(row, 'id')),
    memberId: crewMemberId(text(row, 'memberId')),
    name: text(row, 'name'),
    cadence: parseCadence(text(row, 'cadence')),
    target: target === undefined ? undefined : parseTarget(target),
    status: text(row, 'status') as Habit['status'],
    startsOn: parseCivilDate(text(row, 'startsOn')),
    endsOn: endsOn === undefined ? undefined : parseCivilDate(endsOn),
    tags: words(row, 'tags'),
    graceDays: count(row, 'graceDays'),
    createdAt: parseInstant(text(row, 'createdAt')),
  });
}

export function encodeCheckIn(entry: CheckIn): Row {
  return {
    id: entry.id,
    habitId: entry.habitId,
    memberId: entry.memberId,
    at: formatInstant(entry.at),
    recordedAt: formatInstant(entry.recordedAt),
    reading: entry.reading ?? null,
    note: entry.note,
    source: entry.source,
    idempotencyKey: entry.idempotencyKey ?? null,
    voidedAt: entry.voidedAt === undefined ? null : formatInstant(entry.voidedAt),
  };
}

export function decodeCheckIn(row: Row): CheckIn {
  const voidedAt = optionalText(row, 'voidedAt');
  const base = checkIn({
    id: checkInId(text(row, 'id')),
    habitId: habitId(text(row, 'habitId')),
    memberId: crewMemberId(text(row, 'memberId')),
    at: parseInstant(text(row, 'at')),
    recordedAt: parseInstant(text(row, 'recordedAt')),
    source: text(row, 'source') as CheckInSource,
    reading: optionalCount(row, 'reading'),
    note: optionalText(row, 'note'),
    idempotencyKey: optionalText(row, 'idempotencyKey'),
  });
  return voidedAt === undefined ? base : { ...base, voidedAt: parseInstant(voidedAt) };
}

export function encodeOverride(entry: RosterOverride): Row {
  return {
    memberId: entry.memberId,
    range: formatDateRange(entry.range),
    role: entry.role,
    reason: entry.reason,
    note: entry.note,
    enteredAt: formatInstant(entry.enteredAt),
  };
}

export function decodeOverride(row: Row): RosterOverride {
  return rosterOverride({
    memberId: crewMemberId(text(row, 'memberId')),
    range: parseDateRange(text(row, 'range')),
    role: text(row, 'role') as DayRole,
    reason: text(row, 'reason') as OverrideReason,
    note: optionalText(row, 'note'),
    enteredAt: parseInstant(text(row, 'enteredAt')),
  });
}

export function encodeShield(entry: Shield): Row {
  return {
    memberId: entry.memberId,
    range: formatDateRange(entry.range),
    reason: entry.reason,
    note: entry.note,
    grantedBy: entry.grantedBy ?? null,
    grantedAt: formatInstant(entry.grantedAt),
  };
}

export function decodeShield(row: Row): Shield {
  const grantedBy = optionalText(row, 'grantedBy');
  return shield({
    memberId: crewMemberId(text(row, 'memberId')),
    range: parseDateRange(text(row, 'range')),
    reason: text(row, 'reason') as ShieldReason,
    note: optionalText(row, 'note'),
    grantedBy: grantedBy === undefined ? undefined : crewMemberId(grantedBy),
    grantedAt: parseInstant(text(row, 'grantedAt')),
  });
}

export function encodeCrew(entry: Crew): Row {
  return { id: entry.id, name: entry.name, site: entry.site, siteZone: entry.siteZone };
}

export function decodeCrew(row: Row): Crew {
  return crew({
    id: crewId(text(row, 'id')),
    name: text(row, 'name'),
    site: text(row, 'site'),
    siteZone: text(row, 'siteZone'),
  });
}

export function encodeMembership(entry: Membership): Row {
  return {
    crewId: entry.crewId,
    memberId: entry.memberId,
    role: entry.role,
    from: formatCivilDate(entry.from),
    to: entry.to === undefined ? null : formatCivilDate(entry.to),
  };
}

export function decodeMembership(row: Row): Membership {
  const to = optionalText(row, 'to');
  return membership({
    crewId: crewId(text(row, 'crewId')),
    memberId: crewMemberId(text(row, 'memberId')),
    role: text(row, 'role') as CrewRole,
    from: parseCivilDate(text(row, 'from')),
    to: to === undefined ? undefined : parseCivilDate(to),
  });
}

export function encodeSharing(preference: SharingPreference): Row {
  return {
    memberId: preference.memberId,
    fallback: preference.fallback,
    perHabit: [...preference.perHabit].map(([id, visibility]) => `${id}=${visibility}`),
  };
}

export function decodeSharing(row: Row): SharingPreference {
  const perHabit: [HabitId, Visibility][] = words(row, 'perHabit').map((entry) => {
    const at = entry.indexOf('=');
    if (at < 0) throw ValidationError.field('perHabit', `is not a pair: ${entry}`);
    return [habitId(entry.slice(0, at)), entry.slice(at + 1) as Visibility];
  });
  return sharingPreference({
    memberId: crewMemberId(text(row, 'memberId')),
    fallback: text(row, 'fallback') as Visibility,
    perHabit,
  });
}

export function encodePolicy(memberId: CrewMemberId, policy: NudgePolicy): Row {
  return {
    memberId,
    quietHours: formatClockRange(policy.quietHours),
    minGapMinutes: policy.minGapMinutes,
    maxPerDay: policy.maxPerDay,
    leadMinutes: policy.leadMinutes,
    roles: [...policy.roles],
    channel: policy.channel,
  };
}

export function decodePolicy(row: Row): { memberId: CrewMemberId; policy: NudgePolicy } {
  return {
    memberId: crewMemberId(text(row, 'memberId')),
    policy: nudgePolicy({
      quietHours: text(row, 'quietHours'),
      minGapMinutes: count(row, 'minGapMinutes'),
      maxPerDay: count(row, 'maxPerDay'),
      leadMinutes: count(row, 'leadMinutes'),
      roles: words(row, 'roles'),
      channel: text(row, 'channel') as NudgeChannel,
    }),
  };
}
