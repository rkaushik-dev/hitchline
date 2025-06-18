import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildApp } from '../../src/http/app.js';
import { readConfig, type ApiToken, type Config } from '../../src/config/env.js';
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
const adminToken: ApiToken = { token: 'admin-token-0123456', memberId: bo, scopes: ['admin'] };

const config: Config = {
  ...readConfig({}),
  store: 'memory',
  logLevel: 'silent',
  tokens: [adaToken, boToken, adminToken],
};

type Reply = Awaited<ReturnType<FastifyInstance['inject']>>;
let app: FastifyInstance;
let store: MemoryStore;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
  app = buildApp({ config, ports: store });
  await store.members.save(aMember());
  await store.members.save(aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' }));
});

afterEach(async () => {
  await app.close();
});

function call(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH',
  url: string,
  token: string,
  payload?: Record<string, unknown>,
): Promise<Reply> {
  const headers = { authorization: `Bearer ${token}` };
  if (payload === undefined) return app.inject({ method, url, headers });
  return app.inject({ method, url, headers, payload });
}

describe('the whole api', () => {
  it('answers the health check without a token', async () => {
    const response = await app.inject({ method: 'GET', url: '/health' });
    expect(response.statusCode).toBe(200);
  });

  it('serves every family of route', async () => {
    const created = await call('POST', '/habits', adaToken.token, {
      memberId: ada,
      name: 'Twenty minutes on the treadmill',
      cadence: '1/onshift-day',
      startsOn: '2025-01-06',
    });
    expect(created.statusCode).toBe(201);
    const habitId = created.json<{ id: string }>().id;

    expect((await call('POST', '/check-ins', adaToken.token, { habitId })).statusCode).toBe(201);
    const window = 'window=2025-01-06..2025-01-19';
    expect((await call('GET', `/members/${ada}/roster?${window}`, adaToken.token)).statusCode).toBe(
      200,
    );
    expect(
      (await call('GET', `/members/${ada}/adherence?${window}`, adaToken.token)).statusCode,
    ).toBe(200);
    expect((await call('GET', `/members/${ada}/nudges?${window}`, adaToken.token)).statusCode).toBe(
      200,
    );
  });
});

describe('crews over http', () => {
  async function makeCrew(): Promise<string> {
    const response = await call('POST', '/crews', adminToken.token, {
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    return response.json<{ id: string }>().id;
  }

  it('creates a crew and enrols somebody', async () => {
    const crewId = await makeCrew();
    const enrolled = await call('POST', `/crews/${crewId}/members`, adminToken.token, {
      memberId: ada,
      from: '2025-01-01',
    });
    expect(enrolled.statusCode).toBe(201);
    expect(enrolled.json<{ role: string }>().role).toBe('member');
  });

  it('needs the admin scope to create one', async () => {
    const response = await call('POST', '/crews', adaToken.token, {
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    expect(response.statusCode).toBe(403);
  });

  it('lists who was in a crew on a date', async () => {
    const crewId = await makeCrew();
    await call('POST', `/crews/${crewId}/members`, adminToken.token, {
      memberId: ada,
      from: '2025-01-01',
    });
    const response = await call('GET', `/crews/${crewId}/members?on=2025-02-01`, adaToken.token);
    const body = response.json<{ members: { displayName: string }[] }>();
    expect(body.members.map((member) => member.displayName)).toEqual(['Ada Vasquez']);
  });

  it('needs a date to list a crew', async () => {
    const crewId = await makeCrew();
    expect((await call('GET', `/crews/${crewId}/members`, adaToken.token)).statusCode).toBe(400);
  });

  it('refuses to enrol somebody already in another crew', async () => {
    const first = await makeCrew();
    const second = await makeCrew();
    await call('POST', `/crews/${first}/members`, adminToken.token, {
      memberId: ada,
      from: '2025-01-01',
    });
    const clash = await call('POST', `/crews/${second}/members`, adminToken.token, {
      memberId: ada,
      from: '2025-02-01',
    });
    expect(clash.statusCode).toBe(409);
  });
});

describe('visibility over http', () => {
  it('sets a fallback', async () => {
    const response = await call('PUT', `/members/${ada}/visibility`, adaToken.token, {
      fallback: 'crew',
    });
    expect(response.statusCode).toBe(200);
    expect(response.json<{ fallback: string }>().fallback).toBe('crew');
  });

  it('sets one habit apart from the fallback', async () => {
    const created = await call('POST', '/habits', adaToken.token, {
      memberId: ada,
      name: 'Bank the sleep hours',
      cadence: '1/day',
    });
    const habitId = created.json<{ id: string }>().id;
    const response = await call('PUT', `/members/${ada}/visibility`, adaToken.token, {
      habitId,
      visibility: 'lead_only',
    });
    const body = response.json<{ perHabit: { habitId: string; visibility: string }[] }>();
    expect(body.perHabit).toEqual([{ habitId, visibility: 'lead_only' }]);
  });

  it('refuses a habit with no visibility beside it', async () => {
    const response = await call('PUT', `/members/${ada}/visibility`, adaToken.token, {
      habitId: 'hab_0123456789ABCDEF',
    });
    expect(response.statusCode).toBe(400);
  });

  it('stops somebody setting another member visibility', async () => {
    const response = await call('PUT', `/members/${ada}/visibility`, boToken.token, {
      fallback: 'crew',
    });
    expect(response.statusCode).toBe(403);
  });
});

describe('nudge policy over http', () => {
  it('reads the default', async () => {
    const response = await call('GET', `/members/${ada}/nudge-policy`, adaToken.token);
    expect(response.json<{ quietHours: string }>().quietHours).toBe('22:00-06:00');
  });

  it('stores a change and reads it back', async () => {
    await call('PUT', `/members/${ada}/nudge-policy`, adaToken.token, {
      maxPerDay: 1,
      quietHours: '23:00-07:00',
    });
    const response = await call('GET', `/members/${ada}/nudge-policy`, adaToken.token);
    const body = response.json<{ maxPerDay: number; quietHours: string }>();
    expect(body.maxPerDay).toBe(1);
    expect(body.quietHours).toBe('23:00-07:00');
  });

  it('refuses a value out of range', async () => {
    const response = await call('PUT', `/members/${ada}/nudge-policy`, adaToken.token, {
      maxPerDay: 99,
    });
    expect(response.statusCode).toBe(400);
  });
});

describe('the metrics endpoint', () => {
  it('counts what it served', async () => {
    await app.inject({ method: 'GET', url: '/health' });
    const response = await app.inject({ method: 'GET', url: '/metrics' });
    expect(response.statusCode).toBe(200);
    expect(response.headers['content-type']).toContain('text/plain');
    expect(response.body).toContain('hitchline_requests_total');
    expect(response.body).toContain('method="GET"');
  });

  it('records how long a request took', async () => {
    await app.inject({ method: 'GET', url: '/health' });
    const response = await app.inject({ method: 'GET', url: '/metrics' });
    expect(response.body).toContain('hitchline_request_ms_count');
  });
});

describe('the request id', () => {
  it('comes back on every response', async () => {
    const response = await app.inject({ method: 'GET', url: '/health' });
    expect(response.headers['x-request-id']).toBeDefined();
  });
});

describe('the write limit', () => {
  it('turns a burst away with 429', async () => {
    const limited = buildApp({
      config,
      ports: memoryStore({ clock: fixedClock('2025-04-16T09:00:00Z'), ids: sequentialIds() }),
      burst: 2,
      writesPerMinute: 60,
    });
    const headers = { authorization: `Bearer ${adaToken.token}` };
    const payload = { name: 'x', site: 'y', siteZone: 'UTC' };
    await limited.inject({ method: 'POST', url: '/crews', headers, payload });
    await limited.inject({ method: 'POST', url: '/crews', headers, payload });
    const third = await limited.inject({ method: 'POST', url: '/crews', headers, payload });
    expect(third.statusCode).toBe(429);
    await limited.close();
  });
});

describe('the crew comparison', () => {
  async function crewWithBoth(): Promise<string> {
    const created = await call('POST', '/crews', adminToken.token, {
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    const id = created.json<{ id: string }>().id;
    await call('POST', `/crews/${id}/members`, adminToken.token, {
      memberId: ada,
      from: '2025-01-01',
    });
    await call('POST', `/crews/${id}/members`, adminToken.token, {
      memberId: bo,
      from: '2025-01-01',
    });
    return id;
  }

  it('lists a line for each member', async () => {
    const id = await crewWithBoth();
    const url = `/crews/${id}/comparison?window=2025-01-06..2025-01-19&on=2025-01-20`;
    const response = await call('GET', url, adaToken.token);
    expect(response.statusCode).toBe(200);
    expect(response.json<{ lines: unknown[] }>().lines).toHaveLength(2);
  });

  it('needs a window and a date', async () => {
    const id = await crewWithBoth();
    const noWindow = await call('GET', `/crews/${id}/comparison?on=2025-01-20`, adaToken.token);
    expect(noWindow.statusCode).toBe(400);
    const noDate = await call(
      'GET',
      `/crews/${id}/comparison?window=2025-01-06..2025-01-19`,
      adaToken.token,
    );
    expect(noDate.statusCode).toBe(400);
  });

  it('says how much it left out', async () => {
    const id = await crewWithBoth();
    await call('POST', '/habits', boToken.token, {
      memberId: bo,
      name: 'Bank the sleep hours',
      cadence: '1/day',
      startsOn: '2025-01-06',
    });
    const url = `/crews/${id}/comparison?window=2025-01-06..2025-01-19&on=2025-01-20`;
    const response = await call('GET', url, adaToken.token);
    const lines = response.json<{ lines: { memberId: string; hidden: number }[] }>().lines;
    expect(lines.find((line) => line.memberId === bo)?.hidden).toBe(1);
  });
});

describe('the api description', () => {
  it('is served without a token', async () => {
    const response = await app.inject({ method: 'GET', url: '/openapi.json' });
    expect(response.statusCode).toBe(200);
    const body = response.json<{ openapi: string; paths: Record<string, unknown> }>();
    expect(body.openapi).toBe('3.1.0');
    expect(Object.keys(body.paths)).toContain('/habits');
  });
});

describe('habit templates', () => {
  it('lists the standard set', async () => {
    const response = await call('GET', '/templates', adaToken.token);
    expect(response.statusCode).toBe(200);
    const body = response.json<{ templates: { key: string }[] }>();
    expect(body.templates.map((template) => template.key)).toContain('rest-hours');
  });

  it('gives somebody a habit from one', async () => {
    const response = await call('POST', '/habits/from-template', adaToken.token, {
      memberId: ada,
      key: 'gym-session',
      startsOn: '2025-01-06',
    });
    expect(response.statusCode).toBe(201);
    const body = response.json<{ name: string; cadence: string; target: string }>();
    expect(body.name).toBe('Twenty minutes in the gym');
    expect(body.cadence).toBe('3/hitch');
    expect(body.target).toBe('at least 20 minutes');
  });

  it('carries the grace window a template asks for', async () => {
    const response = await call('POST', '/habits/from-template', adaToken.token, {
      memberId: ada,
      key: 'rest-hours',
    });
    expect(response.json<{ graceDays: number }>().graceDays).toBe(1);
  });

  it('answers 404 for a template it does not have', async () => {
    const response = await call('POST', '/habits/from-template', adaToken.token, {
      memberId: ada,
      key: 'nothing-here',
    });
    expect(response.statusCode).toBe(404);
  });

  it('stops somebody applying one for another member', async () => {
    const response = await call('POST', '/habits/from-template', boToken.token, {
      memberId: ada,
      key: 'gym-session',
    });
    expect(response.statusCode).toBe(403);
  });
});

describe('readiness', () => {
  it('is served without a token and says what it checked', async () => {
    const response = await app.inject({ method: 'GET', url: '/ready' });
    expect(response.statusCode).toBe(200);
    const body = response.json<{ state: string; checks: { name: string }[] }>();
    expect(body.state).toBe('pass');
    expect(body.checks.map((check) => check.name)).toContain('store');
  });
});
