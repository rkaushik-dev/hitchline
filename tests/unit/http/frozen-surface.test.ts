import { describe, expect, it } from 'vitest';
import { ROUTES } from '../../../src/http/manifest.js';
import { registerRoutes } from '../../../src/http/routes/index.js';
import { buildServer } from '../../../src/http/server.js';
import { readConfig } from '../../../src/config/env.js';
import { memoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';

/**
 * The routes the api promises. This list is the contract: adding to it is a
 * feature, changing or removing a line is a breaking change, and either way
 * somebody has to edit this file on purpose rather than by accident.
 */
const FROZEN = [
  'GET /health',
  'GET /metrics',
  'POST /habits',
  'GET /habits',
  'GET /habits/:habitId',
  'PATCH /habits/:habitId',
  'POST /check-ins',
  'GET /members/:memberId/check-ins',
  'POST /check-ins/:checkInId/void',
  'PATCH /check-ins/:checkInId',
  'GET /members/:memberId/roster',
  'GET /members/:memberId/adherence',
  'GET /members/:memberId/nudges',
  'POST /members/:memberId/sync',
  'POST /crews',
  'GET /crews/:crewId/comparison',
];

function registered(): string[] {
  const app = buildServer({
    config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
    ports: memoryStore({ clock: fixedClock('2025-08-25T09:00:00Z') }),
  });
  registerRoutes(app, memoryStore({ clock: fixedClock('2025-08-25T09:00:00Z') }));
  const printed = app.printRoutes({ commonPrefix: false });
  void app.close();
  return printed.split('\n');
}

describe('the frozen surface', () => {
  it('is exactly what the manifest describes', () => {
    expect(ROUTES.map((route) => `${route.method} ${route.path}`)).toEqual(FROZEN);
  });

  it('describes only routes the server actually registers', () => {
    const printed = registered().join('\n');
    for (const route of ROUTES) {
      if (route.path === '/metrics') continue;
      const path = route.path.split('/').filter((part) => part.length > 0)[0] ?? '';
      expect(printed).toContain(path);
    }
  });

  it('gives every route a scope somebody chose', () => {
    for (const route of ROUTES) {
      expect(['none', 'read', 'write', 'admin']).toContain(route.scope);
    }
  });

  it('leaves only health and metrics open', () => {
    const open = ROUTES.filter((route) => route.scope === 'none').map((route) => route.path);
    expect(open).toEqual(['/health', '/metrics']);
  });

  it('never has two entries for one method and path', () => {
    const keys = ROUTES.map((route) => `${route.method} ${route.path}`);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('names a path parameter for every one in the path', () => {
    for (const route of ROUTES) {
      const inPath = [...route.path.matchAll(/:([A-Za-z]+)/g)].map((match) => match[1]);
      const declared = route.parameters
        .filter((parameter) => parameter.in === 'path')
        .map((parameter) => parameter.name);
      for (const name of declared) {
        expect(inPath).toContain(name);
      }
    }
  });
});
