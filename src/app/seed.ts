import { crewMember } from '../domain/crew/member.js';
import { crew, membership } from '../domain/crew/crew.js';
import { habit } from '../domain/habit/habit.js';
import { parseCadence } from '../domain/habit/cadence.js';
import { parseTarget } from '../domain/habit/target.js';
import { checkIn } from '../domain/checkin/checkin.js';
import { rotationAssignment } from '../domain/rotation/assignment.js';
import { parseRotationPattern } from '../domain/rotation/pattern.js';
import { roleOn } from '../domain/rotation/assignment.js';
import { checkInId, crewId, crewMemberId, habitId, mintId, type IdKind } from '../domain/ids.js';
import { addDays, parseCivilDate, type CivilDate } from '../domain/time/civil.js';
import { zonedInstant } from '../domain/time/zone.js';
import { parseClockTime } from '../domain/time/clocktime.js';
import type { Ports } from './ports.js';

/**
 * Demo data. Somebody trying the thing out needs a crew with a fortnight of
 * history behind it, or every report reads as empty and the tool looks broken.
 * The generator is deterministic, so two people running it see the same crew
 * and can talk about the same numbers.
 */
export interface SeedOptions {
  readonly from?: string;
  readonly days?: number;
  /** Roughly what share of what is owed gets done, between zero and one. */
  readonly adherence?: number;
  readonly seed?: number;
}

export interface SeedReport {
  readonly crewId: string;
  readonly members: number;
  readonly habits: number;
  readonly checkIns: number;
  readonly from: CivilDate;
  readonly to: CivilDate;
}

interface Person {
  readonly name: string;
  readonly home: string;
  readonly phase: number;
}

const PEOPLE: readonly Person[] = [
  { name: 'Ada Vasquez', home: 'Europe/Lisbon', phase: 0 },
  { name: 'Bo Lindqvist', home: 'Europe/Stockholm', phase: 14 },
  { name: 'Cleo Nkemdirim', home: 'Europe/London', phase: 0 },
  { name: 'Dai Roberts', home: 'Europe/London', phase: 14 },
  { name: 'Eero Salo', home: 'Europe/Helsinki', phase: 0 },
];

const HABITS: readonly { name: string; cadence: string; target?: string }[] = [
  {
    name: 'Twenty minutes on the treadmill',
    cadence: '1/onshift-day',
    target: 'at least 20 minutes',
  },
  { name: 'Bank the sleep hours', cadence: '1/day', target: 'at least 7 count' },
  { name: 'Toolbox talk read through', cadence: '3/hitch' },
  { name: 'Call home', cadence: '1/offshift-block' },
];

/** A small deterministic generator, so a demo looks the same twice running. */
function rng(seed: number): () => number {
  let state = seed >>> 0 || 1;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    return state / 0xffff_ffff;
  };
}

/** Deterministic ids in the domain alphabet, so a demo reads the same twice. */
function idFor(kind: IdKind, index: number): string {
  const bytes = new Uint8Array(16);
  let value = index;
  for (let position = 15; position >= 0 && value > 0; position -= 1) {
    bytes[position] = value % 32;
    value = Math.floor(value / 32);
  }
  return mintId(kind, bytes);
}

export async function seedDemo(ports: Ports, options: SeedOptions = {}): Promise<SeedReport> {
  const from = parseCivilDate(options.from ?? '2025-01-06');
  const days = options.days ?? 28;
  const adherence = Math.min(1, Math.max(0, options.adherence ?? 0.8));
  const next = rng(options.seed ?? 7);

  const site = 'Europe/Oslo';
  const alpha = crew({
    id: crewId(idFor('CrewId', 1)),
    name: 'Alpha production',
    site: 'Grane B',
    siteZone: site,
  });
  await ports.crews.save(alpha);

  let habitCount = 0;
  let checkInCount = 0;

  for (const [index, person] of PEOPLE.entries()) {
    const member = crewMember({
      id: crewMemberId(idFor('CrewMemberId', index + 1)),
      displayName: person.name,
      homeZone: person.home,
      siteZone: site,
      joinedOn: addDays(from, -180),
      rotation: rotationAssignment({
        pattern: parseRotationPattern('14/14'),
        anchor: from,
        phase: person.phase,
      }),
    });
    await ports.members.save(member);
    await ports.crews.addMembership(
      membership({
        crewId: alpha.id,
        memberId: member.id,
        from: addDays(from, -180),
        role: index === 0 ? 'lead' : 'member',
      }),
    );

    for (const [position, template] of HABITS.entries()) {
      habitCount += 1;
      const entry = habit({
        id: habitId(idFor('HabitId', habitCount)),
        memberId: member.id,
        name: template.name,
        cadence: parseCadence(template.cadence),
        target: template.target === undefined ? undefined : parseTarget(template.target),
        startsOn: from,
        createdAt: zonedInstant(site, from, parseClockTime('06:00')),
        tags: position === 0 ? ['fitness'] : [],
      });
      await ports.habits.save(entry);

      for (let day = 0; day < days; day += 1) {
        const date = addDays(from, day);
        const role = roleOn(member.rotation, date);
        if (template.cadence.includes('onshift') && role !== 'onshift') continue;
        if (template.cadence.includes('offshift') && role !== 'offshift') continue;
        if (next() > adherence) continue;

        checkInCount += 1;
        const at = zonedInstant(
          role === 'onshift' ? site : person.home,
          date,
          parseClockTime(next() > 0.5 ? '07:30' : '18:15'),
        );
        await ports.checkIns.add(
          checkIn({
            id: checkInId(idFor('CheckInId', checkInCount)),
            habitId: entry.id,
            memberId: member.id,
            at,
            recordedAt: at,
            source: 'app',
            reading: template.target === undefined ? undefined : Math.round(15 + next() * 20),
          }),
        );
      }
    }
  }

  return {
    crewId: alpha.id,
    members: PEOPLE.length,
    habits: habitCount,
    checkIns: checkInCount,
    from,
    to: addDays(from, days - 1),
  };
}
