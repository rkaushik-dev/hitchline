import { ConflictError, ValidationError } from '../errors.js';
import type { CrewMemberId, HabitId } from '../ids.js';
import { formatInstant, type Instant } from '../time/zone.js';
import type { NudgeChannel } from './policy.js';
import type { PlannedNudge } from './planner.js';

/**
 * What happened to a nudge after it was planned. Planning is pure and cheap to
 * repeat; delivery is neither, so the plan and the record of what was actually
 * sent are kept apart and only this half is written down.
 */
export type DispatchState = 'planned' | 'sent' | 'seen' | 'dropped';

export interface Dispatch {
  readonly key: string;
  readonly habitId: HabitId;
  readonly memberId: CrewMemberId;
  readonly channel: NudgeChannel;
  readonly plannedFor: Instant;
  readonly state: DispatchState;
  readonly stateAt: Instant;
  readonly attempts: number;
  readonly note: string;
}

const MOVES: Record<DispatchState, readonly DispatchState[]> = {
  planned: ['sent', 'dropped'],
  sent: ['seen', 'dropped', 'sent'],
  seen: [],
  dropped: [],
};

export function planDispatch(
  nudge: PlannedNudge,
  memberId: CrewMemberId,
  channel: NudgeChannel,
  at: Instant,
): Dispatch {
  return {
    key: nudge.key,
    habitId: nudge.habitId,
    memberId,
    channel,
    plannedFor: nudge.at,
    state: 'planned',
    stateAt: at,
    attempts: 0,
    note: '',
  };
}

function moveTo(entry: Dispatch, state: DispatchState, at: Instant, note: string): Dispatch {
  if (!MOVES[entry.state].includes(state)) {
    throw new ConflictError(`a nudge cannot go from ${entry.state} to ${state}`);
  }
  if (at.epochMs < entry.stateAt.epochMs) {
    throw ValidationError.field('at', `is before the last move at ${formatInstant(entry.stateAt)}`);
  }
  return {
    ...entry,
    state,
    stateAt: at,
    attempts: state === 'sent' ? entry.attempts + 1 : entry.attempts,
    note: note.trim().slice(0, 240),
  };
}

export function markSent(entry: Dispatch, at: Instant, note = ''): Dispatch {
  return moveTo(entry, 'sent', at, note);
}

export function markSeen(entry: Dispatch, at: Instant): Dispatch {
  return moveTo(entry, 'seen', at, '');
}

export function markDropped(entry: Dispatch, at: Instant, why: string): Dispatch {
  if (why.trim().length === 0) {
    throw ValidationError.field('why', 'is needed when a nudge is dropped');
  }
  return moveTo(entry, 'dropped', at, why);
}

export interface RetryPolicy {
  readonly maxAttempts: number;
  readonly backoffMinutes: number;
}

export const DEFAULT_RETRY: RetryPolicy = { maxAttempts: 3, backoffMinutes: 30 };

/**
 * Whether a sent nudge that nobody has looked at is worth sending again. A
 * person on shift cannot answer a handset, so the backoff is generous and the
 * attempts are few.
 */
export function shouldRetry(entry: Dispatch, now: Instant, policy = DEFAULT_RETRY): boolean {
  if (entry.state !== 'sent') return false;
  if (entry.attempts >= policy.maxAttempts) return false;
  return now.epochMs - entry.stateAt.epochMs >= policy.backoffMinutes * 60_000;
}

export function isSettled(entry: Dispatch): boolean {
  return entry.state === 'seen' || entry.state === 'dropped';
}

export function dueNow(entries: readonly Dispatch[], now: Instant): Dispatch[] {
  return entries.filter(
    (entry) => entry.state === 'planned' && entry.plannedFor.epochMs <= now.epochMs,
  );
}

export function describeDispatch(entry: Dispatch): string {
  const note = entry.note.length === 0 ? '' : ` (${entry.note})`;
  return `${entry.key} ${entry.state} by ${entry.channel} after ${String(entry.attempts)}${note}`;
}
