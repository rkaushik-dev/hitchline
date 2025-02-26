import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../../src/http/server.js';
import { registerAuth } from '../../src/http/auth.js';
import { registerRosterRoutes } from '../../src/http/routes/roster.js';
import { registerAdherenceRoutes } from '../../src/http/routes/adherence.js';
import { readConfig, type ApiToken } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { checkInsService } from '../../src/app/checkins.service.js';
import { crewMemberId, habitId } from '../../src/domain/ids.js';
import { aHabit, aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const treadmill = habitId('hab_0123456789ABCDEF');

const adaToken: ApiToken = {
  token: 'ada-token-0123456789',
  memberId: ada,
  scopes: ['read', 'write'],
};
const boToken: ApiToken = { token: 'bo-token-01234567890', memberId: bo, scopes: ['read'] };
const adminToken: ApiToken = { token: 'admin-token-0123456', memberId: bo, scopes: ['admin'] };

type Reply = Awaited<ReturnType<FastifyInstance['inject']>>;
let app: FastifyInstance;
let store: MemoryStore;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
  app = buildServer({
    config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
    ports: store,
  });
  registerAuth(app, { tokens: [adaToken, boToken, adminToken] });
  registerRosterRoutes(app, store);
  registerAdherenceRoutes(app, store);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-06' }));
});

afterEach(async () => {
  await app.close();
});

function call(
  method: 'GET' | 'POST',
  url: string,
  token: string,
  payload?: Record<string, unknown>,
): Promise<Reply> {
  const headers = { authorization: `Bearer ${token}` };
  if (payload === undefined) return app.inject({ method, url, headers });
  return app.inject({ method, url, headers, payload });
}

describe('reading a roster', () => {
  it('gives a role and a zone for each day', async () => {
    const url = `/members/${ada}/roster?window=2025-01-19..2025-01-21`;
    const response = await call('GET', url, adaToken.token);
    expect(response.statusCode).toBe(200);
    const body = response.json<{ days: { date: string; role: string; zone: string }[] }>();
    expect(body.days).toHaveLength(3);
    expect(body.days[0]).toEqual({
      date: '2025-01-19',
      role: 'onshift',
      source: 'pattern',
      zone: 'Europe/Oslo',
    });
  });

  it('needs a window', async () => {
    const response = await call('GET', `/members/${ada}/roster`, adaToken.token);
    expect(response.statusCode).toBe(400);
  });

  it('refuses a window it cannot read', async () => {
    const url = `/members/${ada}/roster?window=january`;
    expect((await call('GET', url, adaToken.token)).statusCode).toBe(400);
  });

  it('stops somebody reading another member roster', async () => {
    const url = `/members/${ada}/roster?window=2025-01-19..2025-01-21`;
    expect((await call('GET', url, boToken.token)).statusCode).toBe(403);
  });

  it('lets an admin read it', async () => {
    const url = `/members/${ada}/roster?window=2025-01-19..2025-01-21`;
    expect((await call('GET', url, adminToken.token)).statusCode).toBe(200);
  });

  it('finds the hitch a date sits in', async () => {
    const response = await call('GET', `/members/${ada}/hitch?on=2025-01-30`, adaToken.token);
    expect(response.json<{ hitch: string }>().hitch).toBe('2025-01-06..2025-02-02');
  });
});

describe('changing a roster', () => {
  it('records a hold', async () => {
    const response = await call('POST', '/roster/holds', adaToken.token, {
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
      note: 'relief weathered off',
    });
    expect(response.statusCode).toBe(201);
    expect(response.json<{ range: string }>().range).toBe('2025-01-20..2025-01-22');
    expect(await store.overrides.byMember(ada)).toHaveLength(1);
  });

  it('refuses a second hold that clashes', async () => {
    const payload = {
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    };
    await call('POST', '/roster/holds', adaToken.token, payload);
    const second = await call('POST', '/roster/holds', adaToken.token, {
      ...payload,
      range: '2025-01-21..2025-01-25',
    });
    expect(second.statusCode).toBe(409);
  });

  it('needs the write scope', async () => {
    const response = await call('POST', '/roster/holds', boToken.token, {
      memberId: bo,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    expect(response.statusCode).toBe(403);
  });

  it('records a shield', async () => {
    const response = await call('POST', '/roster/shields', adaToken.token, {
      memberId: ada,
      range: '2025-02-03..2025-02-07',
      reason: 'annual_leave',
    });
    expect(response.statusCode).toBe(201);
    expect(response.json<{ grantedBy: string | null }>().grantedBy).toBeNull();
  });

  it('refuses a shield that needs a granter', async () => {
    const response = await call('POST', '/roster/shields', adaToken.token, {
      memberId: ada,
      range: '2025-02-03..2025-02-07',
      reason: 'training',
    });
    expect(response.statusCode).toBe(400);
  });
});

describe('reading adherence', () => {
  beforeEach(async () => {
    const checkIns = checkInsService(store);
    await checkIns.record({ habitId: treadmill, at: '2025-01-06T09:00:00Z' });
    await checkIns.record({ habitId: treadmill, at: '2025-01-07T09:00:00Z' });
  });

  it('reports a window', async () => {
    const url = `/members/${ada}/adherence?window=2025-01-06..2025-01-19`;
    const response = await call('GET', url, adaToken.token);
    const body = response.json<{
      summary: { owed: number; met: number };
      results: unknown[];
      byHitch: unknown[];
    }>();
    expect(body.summary.owed).toBe(14);
    expect(body.summary.met).toBe(2);
    expect(body.results).toHaveLength(14);
    expect(body.byHitch).toHaveLength(1);
  });

  it('reports a streak', async () => {
    const url = `/members/${ada}/habits/${treadmill}/streak?window=2025-01-06..2025-01-07`;
    const response = await call('GET', url, adaToken.token);
    expect(response.json<{ current: number }>().current).toBe(2);
  });

  it('answers 404 for a habit the member does not have', async () => {
    const url = `/members/${ada}/habits/hab_9999999999999999/streak?window=2025-01-06..2025-01-07`;
    expect((await call('GET', url, adaToken.token)).statusCode).toBe(404);
  });

  it('lists what is still open', async () => {
    const url = `/members/${ada}/open?window=2025-01-06..2025-01-31`;
    const response = await call('GET', url, adaToken.token);
    const body = response.json<{ open: { status: string }[] }>();
    expect(body.open.every((entry) => entry.status === 'pending')).toBe(true);
  });

  it('needs a window', async () => {
    expect((await call('GET', `/members/${ada}/adherence`, adaToken.token)).statusCode).toBe(400);
  });
});
