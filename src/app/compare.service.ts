import { NotFoundError } from '../domain/errors.js';
import type { CrewId, CrewMemberId } from '../domain/ids.js';
import { membersOn } from '../domain/crew/crew.js';
import { canRead, sharingPreference } from '../domain/crew/sharing.js';
import { adherenceRate, countByStatus } from '../domain/adherence/match.js';
import { parseCivilDate } from '../domain/time/civil.js';
import { parseDateRange } from '../domain/time/range.js';
import { memberLoader } from './loader.js';
import type { Ports } from './ports.js';

/**
 * A crew read side by side. Two rules matter here and they pull against each
 * other: a lead needs to see whether the crew is keeping up, and a member who
 * has not shared a habit must not appear in a list because of it. So the
 * comparison only ever counts what the viewer is allowed to read, and says
 * how much it left out.
 */
export interface CrewLine {
  readonly memberId: CrewMemberId;
  readonly displayName: string;
  readonly owed: number;
  readonly met: number;
  readonly missed: number;
  readonly rate: number;
  /** Habits left out because the viewer may not read them. */
  readonly hidden: number;
}

export interface CrewComparison {
  readonly crewId: CrewId;
  readonly lines: readonly CrewLine[];
  readonly rate: number;
}

export interface CompareService {
  crew(viewerId: CrewMemberId, crewId: CrewId, window: string, on: string): Promise<CrewComparison>;
}

export function compareService(ports: Ports): CompareService {
  const loader = memberLoader(ports);

  return {
    async crew(
      viewerId: CrewMemberId,
      crewId: CrewId,
      window: string,
      on: string,
    ): Promise<CrewComparison> {
      const crew = await ports.crews.byId(crewId);
      if (crew === undefined) throw new NotFoundError('crew', crewId);

      const date = parseCivilDate(on);
      const range = parseDateRange(window);
      const memberships = await ports.crews.memberships();
      const viewerRole = memberships.find(
        (row) => row.memberId === viewerId && row.crewId === crewId,
      )?.role;

      const lines: CrewLine[] = [];
      for (const membership of membersOn(memberships, crewId, date)) {
        const member = await ports.members.byId(membership.memberId);
        if (member === undefined) continue;
        const preference =
          (await ports.sharing.byMember(member.id)) ?? sharingPreference({ memberId: member.id });

        const loaded = await loader.load(member.id);
        let owed = 0;
        let met = 0;
        let missed = 0;
        let hidden = 0;
        const readable = [];

        for (const habit of loaded.habits) {
          const allowed = canRead({
            viewer: { memberId: viewerId, role: viewerRole ?? 'member' },
            preference,
            habitId: habit.id,
            memberships,
            on: date,
          });
          if (!allowed) {
            hidden += 1;
            continue;
          }
          readable.push(...loader.scoreOne(loaded, habit, range));
        }

        const counts = countByStatus(readable);
        owed = readable.length;
        met = counts.met;
        missed = counts.missed;
        lines.push({
          memberId: member.id,
          displayName: member.displayName,
          owed,
          met,
          missed,
          rate: adherenceRate(readable),
          hidden,
        });
      }

      lines.sort(
        (left, right) =>
          right.rate - left.rate || left.displayName.localeCompare(right.displayName),
      );
      const all = lines.reduce(
        (totals, line) => ({ owed: totals.owed + line.owed, met: totals.met + line.met }),
        { owed: 0, met: 0 },
      );
      return {
        crewId,
        lines,
        rate: all.owed === 0 ? 1 : all.met / all.owed,
      };
    },
  };
}
