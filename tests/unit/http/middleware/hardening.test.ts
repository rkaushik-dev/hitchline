import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import {
  DEFAULT_MAX_BODY_BYTES,
  SECURITY_HEADERS,
  checkBodyLimit,
  isAllowedOrigin,
  registerHardening,
} from '../../../../src/http/middleware/hardening.js';
import { buildServer } from '../../../../src/http/server.js';
import { readConfig } from '../../../../src/config/env.js';
import { memoryStore } from '../../../../src/infra/store/memory.js';
import { fixedClock } from '../../../../src/infra/clock.js';
import { ValidationError } from '../../../../src/domain/errors.js';

let app: FastifyInstance;

beforeEach(() => {
  app = buildServer({
    config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
    ports: memoryStore({ clock: fixedClock('2025-06-18T09:00:00Z') }),
  });
  registerHardening(app, { allowedOrigins: ['https://crew.example'] });
  app.get('/thing', () => ({ ok: true }));
});

afterEach(async () => {
  await app.close();
});

describe('the body limit', () => {
  it('has a sensible default', () => {
    expect(DEFAULT_MAX_BODY_BYTES).toBe(65_536);
  });

  it('refuses a limit that is too small or too large', () => {
    expect(() => checkBodyLimit(10)).toThrow(ValidationError);
    expect(() => checkBodyLimit(64 * 1024 * 1024)).toThrow(/kilobyte and eight megabytes/);
    expect(() => checkBodyLimit(1_024)).not.toThrow();
  });
});

describe('origins', () => {
  it('allows one on the list', () => {
    expect(isAllowedOrigin('https://crew.example', ['https://crew.example'])).toBe(true);
  });

  it('turns away anything else', () => {
    expect(isAllowedOrigin('https://elsewhere.example', ['https://crew.example'])).toBe(false);
    expect(isAllowedOrigin(undefined, ['https://crew.example'])).toBe(false);
    expect(isAllowedOrigin('https://crew.example', [])).toBe(false);
  });
});

describe('the headers', () => {
  it('are sent on every response', async () => {
    const response = await app.inject({ method: 'GET', url: '/thing' });
    for (const name of Object.keys(SECURITY_HEADERS)) {
      expect(response.headers[name]).toBeDefined();
    }
    expect(response.headers['x-content-type-options']).toBe('nosniff');
  });

  it('are sent on an error too', async () => {
    const response = await app.inject({ method: 'GET', url: '/nothing-here' });
    expect(response.statusCode).toBe(404);
    expect(response.headers['x-frame-options']).toBe('DENY');
  });

  it('tell a browser not to cache a reading', async () => {
    const response = await app.inject({ method: 'GET', url: '/thing' });
    expect(response.headers['cache-control']).toBe('no-store');
  });
});

describe('cross origin requests', () => {
  it('answers a preflight from an allowed origin', async () => {
    const response = await app.inject({
      method: 'OPTIONS',
      url: '/thing',
      headers: { origin: 'https://crew.example' },
    });
    expect(response.statusCode).toBe(204);
    expect(response.headers['access-control-allow-methods']).toContain('POST');
  });

  it('turns away a preflight from anywhere else', async () => {
    const response = await app.inject({
      method: 'OPTIONS',
      url: '/thing',
      headers: { origin: 'https://elsewhere.example' },
    });
    expect(response.statusCode).toBe(404);
  });

  it('echoes an allowed origin on an ordinary response', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/thing',
      headers: { origin: 'https://crew.example' },
    });
    expect(response.headers['access-control-allow-origin']).toBe('https://crew.example');
    expect(response.headers['vary']).toBe('origin');
  });

  it('says nothing about an origin it does not know', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/thing',
      headers: { origin: 'https://elsewhere.example' },
    });
    expect(response.headers['access-control-allow-origin']).toBeUndefined();
  });
});
