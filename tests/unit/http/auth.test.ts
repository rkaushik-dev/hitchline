import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import {
  findPrincipal,
  hasScope,
  isScope,
  principalOf,
  readBearer,
  registerAuth,
  requireScope,
  requireSelfOrAdmin,
} from '../../../src/http/auth.js';
import { buildServer } from '../../../src/http/server.js';
import { readConfig, type ApiToken } from '../../../src/config/env.js';
import { memoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { crewMemberId } from '../../../src/domain/ids.js';
import { ForbiddenError, ValidationError } from '../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');

const adaToken: ApiToken = {
  token: 'ada-token-0123456789',
  memberId: ada,
  scopes: ['read', 'write'],
};
const adminToken: ApiToken = {
  token: 'admin-token-0123456',
  memberId: bo,
  scopes: ['admin'],
};

describe('reading the header', () => {
  it('takes a bearer token', () => {
    expect(readBearer('Bearer abc123')).toBe('abc123');
    expect(readBearer('bearer abc123')).toBe('abc123');
  });

  it('ignores anything else', () => {
    expect(readBearer(undefined)).toBeUndefined();
    expect(readBearer('Basic abc123')).toBeUndefined();
    expect(readBearer('abc123')).toBeUndefined();
    expect(readBearer('Bearer ')).toBeUndefined();
  });
});

describe('scopes', () => {
  it('knows the three it uses', () => {
    expect(isScope('write')).toBe(true);
    expect(isScope('root')).toBe(false);
  });

  it('builds a principal from a token', () => {
    expect(principalOf(adaToken)).toEqual({ memberId: ada, scopes: ['read', 'write'] });
  });

  it('refuses a token with no scope it understands', () => {
    expect(() => principalOf({ ...adaToken, scopes: ['root'] })).toThrow(ValidationError);
  });

  it('lets admin stand in for anything', () => {
    const admin = principalOf(adminToken);
    expect(hasScope(admin, 'read')).toBe(true);
    expect(hasScope(admin, 'write')).toBe(true);
    expect(hasScope(principalOf(adaToken), 'admin')).toBe(false);
  });
});

describe('finding a principal', () => {
  const tokens = [adaToken, adminToken];

  it('matches a known token', () => {
    expect(findPrincipal(tokens, 'Bearer ada-token-0123456789')?.memberId).toBe(ada);
  });

  it('answers nothing for an unknown one', () => {
    expect(findPrincipal(tokens, 'Bearer nope')).toBeUndefined();
    expect(findPrincipal(tokens, undefined)).toBeUndefined();
    expect(findPrincipal([], 'Bearer ada-token-0123456789')).toBeUndefined();
  });
});

describe('guarding a request', () => {
  let app: FastifyInstance;

  beforeEach(() => {
    app = buildServer({
      config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
      ports: memoryStore({ clock: fixedClock('2025-02-10T09:00:00Z') }),
    });
    registerAuth(app, { tokens: [adaToken, adminToken] });
    app.get('/needs-read', (request) => ({ who: requireScope(request, 'read').memberId }));
    app.get('/needs-write', (request) => ({ who: requireScope(request, 'write').memberId }));
    app.get('/needs-admin', (request) => ({ who: requireScope(request, 'admin').memberId }));
    app.get('/for/:memberId', (request) => {
      const params = request.params as { memberId: string };
      return { who: requireSelfOrAdmin(request, crewMemberId(params.memberId)).memberId };
    });
  });

  afterEach(async () => {
    await app.close();
  });

  async function get(url: string, token?: string) {
    return app.inject({
      method: 'GET',
      url,
      headers: token === undefined ? {} : { authorization: `Bearer ${token}` },
    });
  }

  it('turns a request with no token away', async () => {
    const response = await get('/needs-read');
    expect(response.statusCode).toBe(403);
    expect(response.json<{ message: string }>().message).toContain('needs a bearer token');
  });

  it('lets a token with the scope through', async () => {
    const response = await get('/needs-read', adaToken.token);
    expect(response.statusCode).toBe(200);
    expect(response.json<{ who: string }>().who).toBe(ada);
  });

  it('turns away a token without the scope', async () => {
    const response = await get('/needs-admin', adaToken.token);
    expect(response.statusCode).toBe(403);
    expect(response.json<{ message: string }>().message).toContain('admin scope');
  });

  it('lets an admin token through anywhere', async () => {
    expect((await get('/needs-write', adminToken.token)).statusCode).toBe(200);
  });

  it('lets somebody act for themselves', async () => {
    expect((await get(`/for/${ada}`, adaToken.token)).statusCode).toBe(200);
  });

  it('stops somebody acting for another member', async () => {
    const response = await get(`/for/${bo}`, adaToken.token);
    expect(response.statusCode).toBe(403);
    expect(response.json<{ message: string }>().message).toContain('may not act for');
  });

  it('lets an admin act for anybody', async () => {
    expect((await get(`/for/${ada}`, adminToken.token)).statusCode).toBe(200);
  });
});

describe('requireScope on its own', () => {
  it('throws without a principal', () => {
    expect(() => requireScope({} as never, 'read')).toThrow(ForbiddenError);
  });
});
