import { describe, expect, it } from 'vitest';
import {
  DEFAULT_RETRY,
  describeDispatch,
  dueNow,
  isSettled,
  markDropped,
  markSeen,
  markSent,
  planDispatch,
  shouldRetry,
} from '../../../../src/domain/nudge/dispatch.js';
import type { PlannedNudge } from '../../../../src/domain/nudge/planner.js';
import { crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ConflictError, ValidationError } from '../../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');

const nudge: PlannedNudge = {
  habitId: treadmill,
  key: 'hab_0123456789ABCDEF:day:2025-06-02',
  at: parseInstant('2025-06-02T18:00:00Z'),
  onDate: parseCivilDate('2025-06-02'),
  reason: 'nothing_yet',
};

const planned = planDispatch(nudge, ada, 'push', parseInstant('2025-06-02T09:00:00Z'));

describe('planning one', () => {
  it('starts unsent with no attempts', () => {
    expect(planned.state).toBe('planned');
    expect(planned.attempts).toBe(0);
    expect(planned.key).toBe(nudge.key);
    expect(planned.channel).toBe('push');
  });
});

describe('moving through the states', () => {
  it('counts an attempt when it is sent', () => {
    const sent = markSent(planned, parseInstant('2025-06-02T18:00:00Z'));
    expect(sent.state).toBe('sent');
    expect(sent.attempts).toBe(1);
  });

  it('counts a second attempt', () => {
    const once = markSent(planned, parseInstant('2025-06-02T18:00:00Z'));
    const twice = markSent(once, parseInstant('2025-06-02T19:00:00Z'));
    expect(twice.attempts).toBe(2);
  });

  it('settles once somebody looks at it', () => {
    const sent = markSent(planned, parseInstant('2025-06-02T18:00:00Z'));
    const seen = markSeen(sent, parseInstant('2025-06-02T18:05:00Z'));
    expect(isSettled(seen)).toBe(true);
    expect(seen.attempts).toBe(1);
  });

  it('refuses to move once it is settled', () => {
    const seen = markSeen(
      markSent(planned, parseInstant('2025-06-02T18:00:00Z')),
      parseInstant('2025-06-02T18:05:00Z'),
    );
    expect(() => markSent(seen, parseInstant('2025-06-02T19:00:00Z'))).toThrow(ConflictError);
  });

  it('refuses a move backwards in time', () => {
    expect(() => markSent(planned, parseInstant('2025-06-01T09:00:00Z'))).toThrow(ValidationError);
  });

  it('needs a reason to drop one', () => {
    expect(() => markDropped(planned, parseInstant('2025-06-02T18:00:00Z'), '  ')).toThrow(
      /is needed when a nudge is dropped/,
    );
    const dropped = markDropped(planned, parseInstant('2025-06-02T18:00:00Z'), 'quiet hours');
    expect(dropped.note).toBe('quiet hours');
    expect(isSettled(dropped)).toBe(true);
  });
});

describe('retries', () => {
  const sent = markSent(planned, parseInstant('2025-06-02T18:00:00Z'));

  it('waits for the backoff', () => {
    expect(shouldRetry(sent, parseInstant('2025-06-02T18:20:00Z'))).toBe(false);
    expect(shouldRetry(sent, parseInstant('2025-06-02T18:31:00Z'))).toBe(true);
  });

  it('gives up after the attempts run out', () => {
    let entry = sent;
    for (let attempt = 1; attempt < DEFAULT_RETRY.maxAttempts; attempt += 1) {
      entry = markSent(entry, parseInstant('2025-06-03T18:00:00Z'));
    }
    expect(entry.attempts).toBe(DEFAULT_RETRY.maxAttempts);
    expect(shouldRetry(entry, parseInstant('2025-06-04T18:00:00Z'))).toBe(false);
  });

  it('never retries something that was seen or never sent', () => {
    expect(shouldRetry(planned, parseInstant('2025-06-03T18:00:00Z'))).toBe(false);
    const seen = markSeen(sent, parseInstant('2025-06-02T18:05:00Z'));
    expect(shouldRetry(seen, parseInstant('2025-06-03T18:00:00Z'))).toBe(false);
  });

  it('takes a tighter policy', () => {
    expect(
      shouldRetry(sent, parseInstant('2025-06-02T18:06:00Z'), {
        maxAttempts: 5,
        backoffMinutes: 5,
      }),
    ).toBe(true);
  });
});

describe('what is due', () => {
  it('picks the planned ones whose moment has come', () => {
    const later = planDispatch(
      { ...nudge, key: 'later', at: parseInstant('2025-06-03T18:00:00Z') },
      ada,
      'push',
      parseInstant('2025-06-02T09:00:00Z'),
    );
    const due = dueNow([planned, later], parseInstant('2025-06-02T18:30:00Z'));
    expect(due.map((entry) => entry.key)).toEqual([nudge.key]);
  });

  it('leaves out anything already sent', () => {
    const sent = markSent(planned, parseInstant('2025-06-02T18:00:00Z'));
    expect(dueNow([sent], parseInstant('2025-06-02T18:30:00Z'))).toEqual([]);
  });
});

describe('describing one', () => {
  it('reads back with its state and note', () => {
    const dropped = markDropped(planned, parseInstant('2025-06-02T18:00:00Z'), 'quiet hours');
    expect(describeDispatch(dropped)).toBe(
      'hab_0123456789ABCDEF:day:2025-06-02 dropped by push after 0 (quiet hours)',
    );
  });
});
