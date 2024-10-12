import { crewMember, type CrewMember } from '../../src/domain/crew/member.js';
import { crewMemberId } from '../../src/domain/ids.js';
import { rotationAssignment } from '../../src/domain/rotation/assignment.js';
import { parseRotationPattern } from '../../src/domain/rotation/pattern.js';
import { parseCivilDate } from '../../src/domain/time/civil.js';

export interface MemberOptions {
  readonly id?: string;
  readonly displayName?: string;
  readonly homeZone?: string;
  readonly siteZone?: string;
  readonly pattern?: string;
  readonly anchor?: string;
  readonly phase?: number;
  readonly joinedOn?: string;
}

/**
 * The default crew member for tests: a fortnight on a Norwegian platform and a
 * fortnight at home in Lisbon, starting on the first Monday of 2025.
 */
export function aMember(options: MemberOptions = {}): CrewMember {
  return crewMember({
    id: crewMemberId(options.id ?? 'mem_0123456789ABCDEF'),
    displayName: options.displayName ?? 'Ada Vasquez',
    homeZone: options.homeZone ?? 'Europe/Lisbon',
    siteZone: options.siteZone ?? 'Europe/Oslo',
    rotation: rotationAssignment({
      pattern: parseRotationPattern(options.pattern ?? '14/14'),
      anchor: parseCivilDate(options.anchor ?? '2025-01-06'),
      phase: options.phase ?? 0,
    }),
    joinedOn: parseCivilDate(options.joinedOn ?? '2024-11-01'),
  });
}

/** Ids that parse, handed out in order, so a test can name several records. */
export function idSequence(prefix: string): () => string {
  let next = 0;
  return () => {
    next += 1;
    return `${prefix}_${next.toString(32).toUpperCase().padStart(16, '0')}`;
  };
}
