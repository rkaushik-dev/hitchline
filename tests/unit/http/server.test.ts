import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import { buildServer } from '../../../src/http/server.js';
import { asDomainError, isCallerFault, problemFor, statusFor } from '../../../src/http/errors.js';
import { readConfig } from '../../../src/config/env.js';
import { memoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import {
  ConflictError,
  DomainError,
  ForbiddenError,
  NotFoundError,
  UnsupportedError,
  ValidationError,
} from '../../../src/domain/errors.js';

const config = { ...readConfig({}), store: 'memory' as const, logLevel: 'silent' as const };
let app: FastifyInstance;

beforeEach(() => {
  app = buildServer({
    config,
    ports: memoryStore({ clock: fixedClock('2025-02-10T09:00:00Z') }),
  });
});

afterEach(async () => {
  await app.close();
});

describe('mapping errors to statuses', () => {
  it('maps each domain code', () => {
    expect(statusFor(new ValidationError('bad'))).toBe(400);
    expect(statusFor(new NotFoundError('habit', 'hab_1'))).toBe(404);
    expect(statusFor(new ConflictError('clash'))).toBe(409);
    expect(statusFor(new ForbiddenError('no'))).toBe(403);
    expect(statusFor(new UnsupportedError('not yet'))).toBe(422);
  });

  it('maps anything else to five hundred', () => {
    expect(statusFor(new Error('boom'))).toBe(500);
    expect(statusFor('boom')).toBe(500);
  });

  it('answers with the domain message and details', () => {
    const problem = problemFor(ValidationError.field('cadence', 'is not recognised'));
    expect(problem.code).toBe('validation_failed');
    expect(problem.details).toEqual([{ path: 'cadence', message: 'is not recognised' }]);
  });

  it('says nothing revealing about an unexpected failure', () => {
    const problem = problemFor(new Error('connection string is postgres://secret'));
    expect(problem.code).toBe('internal_error');
    expect(problem.message).not.toContain('secret');
  });

  it('knows which failures are the caller fault', () => {
    expect(isCallerFault(new NotFoundError('habit', 'hab_1'))).toBe(true);
    expect(isCallerFault(new UnsupportedError('not yet'))).toBe(false);
    expect(isCallerFault(new Error('boom'))).toBe(false);
  });

  it('wraps a plain error as a domain one', () => {
    const wrapped = asDomainError(new Error('boom'));
    expect(wrapped).toBeInstanceOf(DomainError);
    expect(wrapped.message).toBe('boom');
    expect(asDomainError(new ConflictError('clash')).code).toBe('conflict');
  });
});

describe('the health route', () => {
  it('answers ok with the clock reading', async () => {
    const response = await app.inject({ method: 'GET', url: '/health' });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      status: 'ok',
      at: '2025-02-10T09:00:00.000Z',
      store: 'memory',
    });
  });
});

describe('unknown routes', () => {
  it('answer with a problem body rather than html', async () => {
    const response = await app.inject({ method: 'GET', url: '/nothing-here' });
    expect(response.statusCode).toBe(404);
    const body = response.json<{ code: string; message: string }>();
    expect(body.code).toBe('not_found');
    expect(body.message).toContain('/nothing-here');
  });
});

describe('the error handler', () => {
  it('turns a thrown domain error into its status', async () => {
    app.get('/boom', () => {
      throw new ConflictError('two crews claim the same berth');
    });
    const response = await app.inject({ method: 'GET', url: '/boom' });
    expect(response.statusCode).toBe(409);
    expect(response.json()).toMatchObject({
      code: 'conflict',
      message: 'two crews claim the same berth',
    });
  });

  it('turns anything else into a five hundred', async () => {
    app.get('/kaboom', () => {
      throw new Error('unexpected');
    });
    const response = await app.inject({ method: 'GET', url: '/kaboom' });
    expect(response.statusCode).toBe(500);
    expect(response.json()).toMatchObject({ code: 'internal_error' });
  });
});
