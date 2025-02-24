import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../../src/http/server.js';
import { registerAuth } from '../../src/http/auth.js';
import { registerHabitRoutes } from '../../src/http/routes/habits.js';
import { registerCheckInRoutes } from '../../src/http/routes/checkins.js';
import { readConfig, type ApiToken } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { crewMemberId } from '../../src/domain/ids.js';
import { aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');

const adaToken: ApiToken = {
  token: 'ada-token-0123456789',
  memberId: ada,
  scopes: ['read', 'write'],
};
const boToken: ApiToken = {
  token: 'bo-token-01234567890',
  memberId: bo,
  scopes: ['read', 'write'],
};
const readerToken: ApiToken = { token: 'reader-token-012345', memberId: ada, scopes: ['read'] };

let app: FastifyInstance;
let store: MemoryStore;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-10T09:00:00Z'), ids: sequentialIds() });
  app = buildServer({
    config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
    ports: store,
  });
  registerAuth(app, { tokens: [adaToken, boToken, readerToken] });
  registerHabitRoutes(app, store);
  registerCheckInRoutes(app, store);
  await store.members.save(aMember());
  await store.members.save(aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' }));
});

afterEach(async () => {
  await app.close();
});

type Reply = Awaited<ReturnType<FastifyInstance['inject']>>;

function call(
  method: 'GET' | 'POST' | 'PATCH',
  url: string,
  token: string,
  payload?: Record<string, unknown>,
): Promise<Reply> {
  const headers = { authorization: `Bearer ${token}` };
  if (payload === undefined) {
    return app.inject({ method, url, headers });
  }
  return app.inject({ method, url, headers, payload });
}

async function createHabit(
  token = adaToken.token,
  overrides: Record<string, unknown> = {},
): Promise<Reply> {
  const response = await call('POST', '/habits', token, {
    memberId: ada,
    name: 'Twenty minutes on the treadmill',
    cadence: '1/day',
    startsOn: '2025-01-06',
    ...overrides,
  });
  return response;
}

describe('creating a habit', () => {
  it('answers 201 with the stored habit', async () => {
    const response = await createHabit();
    expect(response.statusCode).toBe(201);
    const body = response.json<{ id: string; cadence: string }>();
    expect(body.id.startsWith('hab_')).toBe(true);
    expect(body.cadence).toBe('1/day');
  });

  it('refuses a body it cannot read', async () => {
    const response = await call('POST', '/habits', adaToken.token, { memberId: ada });
    expect(response.statusCode).toBe(400);
    expect(response.json<{ code: string }>().code).toBe('validation_failed');
  });

  it('refuses a cadence the domain does not know', async () => {
    const response = await createHabit(adaToken.token, { cadence: 'every day' });
    expect(response.statusCode).toBe(400);
    expect(response.json<{ message: string }>().message).toContain('3/hitch');
  });

  it('stops somebody creating a habit for another member', async () => {
    const response = await createHabit(boToken.token);
    expect(response.statusCode).toBe(403);
  });

  it('turns away a request with no token', async () => {
    const response = await app.inject({ method: 'POST', url: '/habits', payload: {} });
    expect(response.statusCode).toBe(403);
  });
});

describe('reading habits', () => {
  it('lists a member habits', async () => {
    await createHabit();
    const response = await call('GET', `/habits?memberId=${ada}`, adaToken.token);
    expect(response.statusCode).toBe(200);
    expect(response.json<{ habits: unknown[] }>().habits).toHaveLength(1);
  });

  it('needs a member id', async () => {
    const response = await call('GET', '/habits', adaToken.token);
    expect(response.statusCode).toBe(400);
  });

  it('reads one by id', async () => {
    const created = await createHabit();
    const id = created.json<{ id: string }>().id;
    const response = await call('GET', `/habits/${id}`, adaToken.token);
    expect(response.json<{ name: string }>().name).toBe('Twenty minutes on the treadmill');
  });

  it('answers 404 for an id it does not hold', async () => {
    const response = await call('GET', '/habits/hab_9999999999999999', adaToken.token);
    expect(response.statusCode).toBe(404);
  });

  it('stops somebody reading another member habit', async () => {
    const created = await createHabit();
    const id = created.json<{ id: string }>().id;
    expect((await call('GET', `/habits/${id}`, boToken.token)).statusCode).toBe(403);
  });
});

describe('changing a habit', () => {
  it('renames it', async () => {
    const id = (await createHabit()).json<{ id: string }>().id;
    const response = await call('PATCH', `/habits/${id}`, adaToken.token, { name: 'Row hard' });
    expect(response.json<{ name: string }>().name).toBe('Row hard');
  });

  it('clears a target with null', async () => {
    const id = (await createHabit(adaToken.token, { target: 'at least 20 minutes' })).json<{
      id: string;
    }>().id;
    const response = await call('PATCH', `/habits/${id}`, adaToken.token, { target: null });
    expect(response.json<{ target: string | null }>().target).toBeNull();
  });

  it('needs the write scope', async () => {
    const id = (await createHabit()).json<{ id: string }>().id;
    const response = await call('PATCH', `/habits/${id}`, readerToken.token, { name: 'Row hard' });
    expect(response.statusCode).toBe(403);
  });

  it('refuses an empty body', async () => {
    const id = (await createHabit()).json<{ id: string }>().id;
    expect((await call('PATCH', `/habits/${id}`, adaToken.token, {})).statusCode).toBe(400);
  });
});

describe('recording a check in', () => {
  let habitIdValue: string;

  beforeEach(async () => {
    habitIdValue = (await createHabit()).json<{ id: string }>().id;
  });

  it('answers 201 with the stored row', async () => {
    const response = await call('POST', '/check-ins', adaToken.token, { habitId: habitIdValue });
    expect(response.statusCode).toBe(201);
    expect(response.json<{ habitId: string }>().habitId).toBe(habitIdValue);
  });

  it('answers 200 when an idempotency key repeats', async () => {
    const payload = { habitId: habitIdValue, idempotencyKey: 'watch-2025-01-10' };
    const first = await call('POST', '/check-ins', adaToken.token, payload);
    const second = await call('POST', '/check-ins', adaToken.token, payload);
    expect(first.statusCode).toBe(201);
    expect(second.statusCode).toBe(200);
    expect(second.json<{ id: string }>().id).toBe(first.json<{ id: string }>().id);
  });

  it('refuses a time in the future', async () => {
    const response = await call('POST', '/check-ins', adaToken.token, {
      habitId: habitIdValue,
      at: '2025-01-20T09:00:00Z',
    });
    expect(response.statusCode).toBe(400);
  });

  it('stops somebody checking in for another member', async () => {
    const response = await call('POST', '/check-ins', boToken.token, { habitId: habitIdValue });
    expect(response.statusCode).toBe(403);
  });

  it('lists what a member has recorded', async () => {
    await call('POST', '/check-ins', adaToken.token, { habitId: habitIdValue });
    const response = await call('GET', `/members/${ada}/check-ins`, adaToken.token);
    const body = response.json<{ checkIns: { onDate: string }[] }>();
    expect(body.checkIns).toHaveLength(1);
    expect(body.checkIns[0]?.onDate).toBe('2025-01-10');
  });

  it('narrows a list to a window', async () => {
    await call('POST', '/check-ins', adaToken.token, { habitId: habitIdValue });
    const url = `/members/${ada}/check-ins?window=2025-01-01..2025-01-05`;
    expect(
      (await call('GET', url, adaToken.token)).json<{ checkIns: unknown[] }>().checkIns,
    ).toHaveLength(0);
  });

  it('voids one', async () => {
    const created = await call('POST', '/check-ins', adaToken.token, { habitId: habitIdValue });
    const id = created.json<{ id: string }>().id;
    expect((await call('POST', `/check-ins/${id}/void`, adaToken.token)).statusCode).toBe(200);
    const listed = await call('GET', `/members/${ada}/check-ins`, adaToken.token);
    expect(listed.json<{ checkIns: unknown[] }>().checkIns).toHaveLength(0);
  });

  it('amends a reading', async () => {
    const created = await call('POST', '/check-ins', adaToken.token, {
      habitId: habitIdValue,
      reading: 20,
    });
    const id = created.json<{ id: string }>().id;
    const response = await call('PATCH', `/check-ins/${id}`, adaToken.token, { reading: 25 });
    expect(response.json<{ reading: number }>().reading).toBe(25);
  });

  it('answers 404 for a check in it does not hold', async () => {
    const response = await call('POST', '/check-ins/chk_9999999999999999/void', adaToken.token);
    expect(response.statusCode).toBe(404);
  });
});
