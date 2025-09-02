import { describe, expect, it } from 'vitest';
import {
  checkInView,
  crewView,
  habitView,
  memberView,
  membershipView,
  nudgeView,
  overrideView,
  resolvedView,
  resultView,
  shieldView,
  streakView,
  summaryView,
} from '../../../src/http/views.js';
import { checkIn } from '../../../src/domain/checkin/checkin.js';
import { resolveCheckIn } from '../../../src/domain/checkin/resolve.js';
import { crew, membership } from '../../../src/domain/crew/crew.js';
import { rosterOverride } from '../../../src/domain/rotation/override.js';
import { shield } from '../../../src/domain/adherence/shield.js';
import { obligation } from '../../../src/domain/schedule/obligation.js';
import { NO_STREAK } from '../../../src/domain/adherence/streak.js';
import { checkInId, crewId, crewMemberId, habitId } from '../../../src/domain/ids.js';
import { parseCivilDate } from '../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../src/domain/time/range.js';
import { parseInstant } from '../../../src/domain/time/zone.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const r = parseDateRange;
const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');

describe('habit view', () => {
  it('formats the cadence and leaves an absent target null', () => {
    const view = habitView(aHabit());
    expect(view.cadence).toBe('1/onshift-day');
    expect(view.target).toBeNull();
    expect(view.endsOn).toBeNull();
  });

  it('formats a target and an end date when set', () => {
    const view = habitView(aHabit({ target: 'at least 20 minutes', endsOn: '2025-06-30' }));
    expect(view.target).toBe('at least 20 minutes');
    expect(view.endsOn).toBe('2025-06-30');
  });
});

describe('member view', () => {
  it('writes the rotation as a pattern and an anchor', () => {
    const view = memberView(aMember());
    expect(view.pattern).toBe('14on 14off');
    expect(view.anchor).toBe('2025-01-06');
    expect(view.phase).toBe(0);
  });
});

describe('check in views', () => {
  const entry = checkIn({
    id: checkInId('chk_0123456789ABCDEF'),
    habitId: treadmill,
    memberId: ada,
    at: parseInstant('2025-01-10T07:30:00Z'),
    recordedAt: parseInstant('2025-01-10T07:31:00Z'),
    source: 'watch',
    reading: 22,
  });

  it('carries no rotation day until it is resolved', () => {
    const view = checkInView(entry);
    expect('onDate' in view).toBe(false);
    expect('zone' in view).toBe(false);
    expect(view.reading).toBe(22);
  });

  it('carries the rotation day once resolved', () => {
    const view = resolvedView(resolveCheckIn(aMember(), entry));
    expect(view.onDate).toBe('2025-01-10');
    expect(view.zone).toBe('Europe/Oslo');
  });

  it('writes an absent reading as null', () => {
    expect(checkInView({ ...entry, reading: undefined }).reading).toBeNull();
  });
});

describe('result and summary views', () => {
  const result = {
    obligation: obligation({
      habitId: treadmill,
      memberId: ada,
      scope: 'hitch' as const,
      window: r('2025-01-06..2025-02-02'),
      times: 3,
    }),
    count: 2,
    total: 40,
    status: 'partial' as const,
    progress: 2 / 3,
  };

  it('rounds progress to two places', () => {
    expect(resultView(result).progress).toBe(0.67);
  });

  it('writes the window and the due day', () => {
    const view = resultView(result);
    expect(view.window).toBe('2025-01-06..2025-02-02');
    expect(view.dueOn).toBe('2025-02-02');
    expect(view.scope).toBe('hitch');
  });

  it('rounds a summary rate', () => {
    const view = summaryView({
      range: r('2025-01-06..2025-01-19'),
      owed: 3,
      met: 1,
      partial: 1,
      missed: 1,
      pending: 0,
      shieldedDays: 0,
      rate: 1 / 3,
    });
    expect(view.rate).toBe(0.33);
    expect(view.window).toBe('2025-01-06..2025-01-19');
  });

  it('writes an empty streak with nulls', () => {
    expect(streakView(NO_STREAK)).toEqual({
      current: 0,
      longest: 0,
      shielded: 0,
      lastMetOn: null,
      brokenOn: null,
    });
  });
});

describe('roster views', () => {
  it('writes an override', () => {
    const view = overrideView(
      rosterOverride({
        memberId: ada,
        range: r('2025-01-20..2025-01-22'),
        role: 'onshift',
        reason: 'held_over',
        enteredAt: parseInstant('2025-01-18T10:00:00Z'),
      }),
    );
    expect(view['range']).toBe('2025-01-20..2025-01-22');
    expect(view['reason']).toBe('held_over');
  });

  it('writes a shield with a null granter when self declared', () => {
    const view = shieldView(
      shield({
        memberId: ada,
        range: r('2025-01-20..2025-01-26'),
        reason: 'annual_leave',
        grantedAt: parseInstant('2025-01-18T10:00:00Z'),
      }),
    );
    expect(view['grantedBy']).toBeNull();
  });
});

describe('crew views', () => {
  it('writes a crew and a membership', () => {
    const alpha = crew({
      id: crewId('crw_0123456789ABCDEF'),
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    expect(crewView(alpha)['site']).toBe('Grane B');
    const entry = membership({
      crewId: alpha.id,
      memberId: ada,
      from: parseCivilDate('2025-01-01'),
    });
    expect(membershipView(entry)['to']).toBeNull();
  });
});

describe('nudge view', () => {
  it('writes the instant and the day', () => {
    const view = nudgeView({
      habitId: treadmill,
      key: 'hab_0123456789ABCDEF:day:2025-01-10',
      at: parseInstant('2025-01-10T19:00:00Z'),
      onDate: parseCivilDate('2025-01-10'),
      reason: 'nothing_yet',
    });
    expect(view['at']).toBe('2025-01-10T19:00:00.000Z');
    expect(view['onDate']).toBe('2025-01-10');
  });
});
