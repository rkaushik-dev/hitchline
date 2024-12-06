import { ValidationError } from '../errors.js';
import { DAY_ROLES, isDayRole, type DayRole } from '../rotation/pattern.js';
import {
  addMinutes,
  containsTime,
  formatClockRange,
  minutesSinceMidnight,
  parseClockRange,
  wrapsMidnight,
  type ClockRange,
  type ClockTime,
} from '../time/clocktime.js';

/**
 * When a person may be pinged and when they may not. Rest hours on a platform
 * are not a preference, they are a rule the operator is audited on, so a nudge
 * that lands inside them is a defect rather than an annoyance.
 */
export type NudgeChannel = 'push' | 'email' | 'tannoy' | 'none';

export interface NudgePolicy {
  readonly quietHours: ClockRange;
  readonly minGapMinutes: number;
  readonly maxPerDay: number;
  /** How long before a window closes the first nudge goes out. */
  readonly leadMinutes: number;
  readonly roles: readonly DayRole[];
  readonly channel: NudgeChannel;
}

export interface NudgePolicyInput {
  readonly quietHours?: string;
  readonly minGapMinutes?: number;
  readonly maxPerDay?: number;
  readonly leadMinutes?: number;
  readonly roles?: readonly string[];
  readonly channel?: NudgeChannel;
}

const MAX_PER_DAY_LIMIT = 12;
const MAX_GAP_MINUTES = 24 * 60;
const MAX_LEAD_MINUTES = 72 * 60;

export const DEFAULT_POLICY: NudgePolicy = {
  quietHours: parseClockRange('22:00-06:00'),
  minGapMinutes: 180,
  maxPerDay: 3,
  leadMinutes: 240,
  roles: ['onshift', 'offshift', 'standby'],
  channel: 'push',
};

export function nudgePolicy(input: NudgePolicyInput = {}): NudgePolicy {
  const minGapMinutes = input.minGapMinutes ?? DEFAULT_POLICY.minGapMinutes;
  const maxPerDay = input.maxPerDay ?? DEFAULT_POLICY.maxPerDay;
  const leadMinutes = input.leadMinutes ?? DEFAULT_POLICY.leadMinutes;

  if (!Number.isInteger(minGapMinutes) || minGapMinutes < 1 || minGapMinutes > MAX_GAP_MINUTES) {
    throw ValidationError.field('minGapMinutes', `must be between 1 and ${MAX_GAP_MINUTES}`);
  }
  if (!Number.isInteger(maxPerDay) || maxPerDay < 0 || maxPerDay > MAX_PER_DAY_LIMIT) {
    throw ValidationError.field('maxPerDay', `must be between 0 and ${MAX_PER_DAY_LIMIT}`);
  }
  if (!Number.isInteger(leadMinutes) || leadMinutes < 0 || leadMinutes > MAX_LEAD_MINUTES) {
    throw ValidationError.field('leadMinutes', `must be between 0 and ${MAX_LEAD_MINUTES}`);
  }

  const roles = (input.roles ?? DEFAULT_POLICY.roles).map((role) => {
    if (!isDayRole(role)) {
      throw ValidationError.field('roles', `does not name a day role: ${role}`);
    }
    return role;
  });
  if (roles.length === 0) {
    throw ValidationError.field('roles', 'must name at least one day role');
  }

  return {
    quietHours:
      input.quietHours === undefined
        ? DEFAULT_POLICY.quietHours
        : parseClockRange(input.quietHours),
    minGapMinutes,
    maxPerDay,
    leadMinutes,
    roles: [...new Set(roles)].sort(byRoleOrder),
    channel: input.channel ?? DEFAULT_POLICY.channel,
  };
}

function byRoleOrder(left: DayRole, right: DayRole): number {
  return DAY_ROLES.indexOf(left) - DAY_ROLES.indexOf(right);
}

export function isQuiet(policy: NudgePolicy, time: ClockTime): boolean {
  return containsTime(policy.quietHours, time);
}

export function nudgesOnRole(policy: NudgePolicy, role: DayRole): boolean {
  return policy.roles.includes(role);
}

/**
 * The first minute at or after `time` that is not inside quiet hours. Answers
 * the same time when it was already allowed.
 */
export function firstAllowedTime(policy: NudgePolicy, time: ClockTime): ClockTime {
  if (!isQuiet(policy, time)) return time;
  return policy.quietHours.to;
}

/** How many minutes are lost to quiet hours if a nudge is deferred from `time`. */
export function quietMinutesAhead(policy: NudgePolicy, time: ClockTime): number {
  if (!isQuiet(policy, time)) return 0;
  const end = minutesSinceMidnight(policy.quietHours.to);
  const now = minutesSinceMidnight(time);
  return end > now ? end - now : 24 * 60 - now + end;
}

export function isSilent(policy: NudgePolicy): boolean {
  return policy.channel === 'none' || policy.maxPerDay === 0;
}

export function withChannel(policy: NudgePolicy, channel: NudgeChannel): NudgePolicy {
  return { ...policy, channel };
}

export function describePolicy(policy: NudgePolicy): string {
  const quiet = formatClockRange(policy.quietHours);
  const wraps = wrapsMidnight(policy.quietHours) ? ' overnight' : '';
  return `${policy.maxPerDay} a day by ${policy.channel}, quiet ${quiet}${wraps}`;
}

export function shiftedBy(time: ClockTime, minutes: number): ClockTime {
  return addMinutes(time, minutes);
}
