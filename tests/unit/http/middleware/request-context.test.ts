import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import {
  REQUEST_ID_HEADER,
  elapsedMs,
  readRequestId,
  registerRequestContext,
} from '../../../../src/http/middleware/request-context.js';
import { buildServer } from '../../../../src/http/server.js';
import { readConfig } from '../../../../src/config/env.js';
import { memoryStore } from '../../../../src/infra/store/memory.js';
import { fixedClock } from '../../../../src/infra/clock.js';

describe('reading a supplied id', () => {
  it('keeps one that looks like an id', () => {
    expect(readRequestId('req-0123456789')).toBe('req-0123456789');
  });

  it('drops one that does not', () => {
    expect(readRequestId('short')).toBeUndefined();
    expect(readRequestId('has spaces in it')).toBeUndefined();
    expect(readRequestId('x'.repeat(65))).toBeUndefined();
    expect(readRequestId(undefined)).toBeUndefined();
    expect(readRequestId(['a', 'b'])).toBeUndefined();
  });
});

describe('over http', () => {
  let app: FastifyInstance;
  let minted: number;

  beforeEach(() => {
    minted = 0;
    app = buildServer({
      config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
      ports: memoryStore({ clock: fixedClock('2025-04-14T09:00:00Z') }),
    });
    registerRequestContext(app, {
      nextId: () => {
        minted += 1;
        return `req-${String(minted).padStart(8, '0')}`;
      },
      now: () => 1_000,
    });
    app.get('/thing', (request) => ({ id: request.id }));
  });

  afterEach(async () => {
    await app.close();
  });

  it('mints an id and answers with it', async () => {
    const response = await app.inject({ method: 'GET', url: '/thing' });
    expect(response.headers[REQUEST_ID_HEADER]).toBe('req-00000001');
    expect(response.json<{ id: string }>().id).toBe('req-00000001');
  });

  it('mints a new one for each request', async () => {
    await app.inject({ method: 'GET', url: '/thing' });
    const second = await app.inject({ method: 'GET', url: '/thing' });
    expect(second.headers[REQUEST_ID_HEADER]).toBe('req-00000002');
  });

  it('keeps an id the caller sent', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/thing',
      headers: { [REQUEST_ID_HEADER]: 'from-the-gateway-01' },
    });
    expect(response.headers[REQUEST_ID_HEADER]).toBe('from-the-gateway-01');
    expect(minted).toBe(0);
  });

  it('replaces one that does not look like an id', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/thing',
      headers: { [REQUEST_ID_HEADER]: 'nope' },
    });
    expect(response.headers[REQUEST_ID_HEADER]).toBe('req-00000001');
  });

  it('answers with the id even when the route fails', async () => {
    app.get('/boom', () => {
      throw new Error('no');
    });
    const response = await app.inject({ method: 'GET', url: '/boom' });
    expect(response.statusCode).toBe(500);
    expect(response.headers[REQUEST_ID_HEADER]).toBeDefined();
  });
});

describe('elapsed time', () => {
  it('measures from the start of the request', () => {
    expect(elapsedMs({ startedAtMs: 100 } as never, 350)).toBe(250);
  });

  it('reads zero when nothing was recorded', () => {
    expect(elapsedMs({} as never, 350)).toBe(0);
  });

  it('never goes negative on a clock that jumped', () => {
    expect(elapsedMs({ startedAtMs: 500 } as never, 100)).toBe(0);
  });
});
