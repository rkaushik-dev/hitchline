import { ForbiddenError, ValidationError } from '../errors.js';
import type { CrewMemberId, HabitId } from '../ids.js';
import type { CivilDate } from '../time/civil.js';
import { crewOf, type CrewRole, type Membership } from './crew.js';

/**
 * Who may read somebody else's adherence. The default is deliberately closed:
 * a habit tracker that quietly shows a supervisor how much sleep somebody is
 * logging is a surveillance tool, and crews stop using those.
 */
export type Visibility = 'private' | 'lead_only' | 'crew';

export interface SharingPreference {
  readonly memberId: CrewMemberId;
  readonly fallback: Visibility;
  /** Per habit settings, which win over the fallback. */
  readonly perHabit: ReadonlyMap<HabitId, Visibility>;
}

export interface Viewer {
  readonly memberId: CrewMemberId;
  readonly role: CrewRole;
}

export interface SharingInput {
  readonly memberId: CrewMemberId;
  readonly fallback?: Visibility;
  readonly perHabit?: Iterable<readonly [HabitId, Visibility]>;
}

const VISIBILITIES: readonly Visibility[] = ['private', 'lead_only', 'crew'];

export function isVisibility(value: string): value is Visibility {
  return (VISIBILITIES as readonly string[]).includes(value);
}

export function sharingPreference(input: SharingInput): SharingPreference {
  const fallback = input.fallback ?? 'private';
  if (!isVisibility(fallback)) {
    throw ValidationError.field('fallback', `is not a visibility: ${fallback}`);
  }
  return {
    memberId: input.memberId,
    fallback,
    perHabit: new Map(input.perHabit ?? []),
  };
}

export function visibilityFor(preference: SharingPreference, habitId: HabitId): Visibility {
  return preference.perHabit.get(habitId) ?? preference.fallback;
}

export function setHabitVisibility(
  preference: SharingPreference,
  habitId: HabitId,
  visibility: Visibility,
): SharingPreference {
  const perHabit = new Map(preference.perHabit);
  perHabit.set(habitId, visibility);
  return { ...preference, perHabit };
}

export function clearHabitVisibility(
  preference: SharingPreference,
  habitId: HabitId,
): SharingPreference {
  const perHabit = new Map(preference.perHabit);
  perHabit.delete(habitId);
  return { ...preference, perHabit };
}

export interface AccessRequest {
  readonly viewer: Viewer;
  readonly preference: SharingPreference;
  readonly habitId: HabitId;
  readonly memberships: readonly Membership[];
  readonly on: CivilDate;
}

/** True when the viewer may read that habit's adherence for that person. */
export function canRead(request: AccessRequest): boolean {
  const { viewer, preference } = request;
  if (viewer.memberId === preference.memberId) return true;

  const theirs = crewOf(request.memberships, preference.memberId, request.on);
  const mine = crewOf(request.memberships, viewer.memberId, request.on);
  if (theirs === undefined || mine === undefined) return false;
  if (theirs.crewId !== mine.crewId) return false;

  const visibility = visibilityFor(preference, request.habitId);
  if (visibility === 'private') return false;
  if (visibility === 'crew') return true;
  return mine.role !== 'member';
}

export function requireRead(request: AccessRequest): void {
  if (!canRead(request)) {
    throw new ForbiddenError(
      `${request.viewer.memberId} may not read ${request.habitId} for ${request.preference.memberId}`,
    );
  }
}

/** The habits of one person a viewer is allowed to see, in the order given. */
export function readableHabits(
  request: Omit<AccessRequest, 'habitId'>,
  habitIds: readonly HabitId[],
): HabitId[] {
  return habitIds.filter((habitId) => canRead({ ...request, habitId }));
}

export function describeVisibility(visibility: Visibility): string {
  switch (visibility) {
    case 'private':
      return 'only you';
    case 'lead_only':
      return 'you and your crew lead';
    case 'crew':
      return 'everybody on your crew';
  }
}
