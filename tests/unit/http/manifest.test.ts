import { describe, expect, it } from 'vitest';
import { ROUTES, findRoute, openApiDocument, routesByScope } from '../../../src/http/manifest.js';
import { ValidationError } from '../../../src/domain/errors.js';

describe('the manifest', () => {
  it('names a summary and a scope for every route', () => {
    for (const route of ROUTES) {
      expect(route.summary.length).toBeGreaterThan(5);
      expect(['none', 'read', 'write', 'admin']).toContain(route.scope);
    }
  });

  it('leaves health and metrics open', () => {
    expect(routesByScope('none').map((route) => route.path)).toEqual(['/health', '/metrics']);
  });

  it('finds a route by method and path', () => {
    expect(findRoute('POST', '/habits')?.scope).toBe('write');
    expect(findRoute('GET', '/habits')?.scope).toBe('read');
    expect(findRoute('DELETE', '/habits')).toBeUndefined();
  });

  it('marks a window as required where it is', () => {
    const roster = findRoute('GET', '/members/:memberId/roster');
    expect(roster?.parameters.find((p) => p.name === 'window')?.required).toBe(true);
    const checkIns = findRoute('GET', '/members/:memberId/check-ins');
    expect(checkIns?.parameters.find((p) => p.name === 'window')?.required).toBe(false);
  });

  it('describes paging on the list that pages', () => {
    const habits = findRoute('GET', '/habits');
    expect(habits?.parameters.map((p) => p.name)).toContain('after');
  });
});

describe('the openapi document', () => {
  const document = openApiDocument({ version: '0.1.0' });

  it('declares the version of the spec and of the api', () => {
    expect(document['openapi']).toBe('3.1.0');
    expect(document['info']).toMatchObject({ title: 'hitchline', version: '0.1.0' });
  });

  it('rewrites path parameters into braces', () => {
    const paths = document['paths'] as Record<string, unknown>;
    expect(Object.keys(paths)).toContain('/members/{memberId}/roster');
    expect(Object.keys(paths)).not.toContain('/members/:memberId/roster');
  });

  it('puts two methods on one path together', () => {
    const paths = document['paths'] as Record<string, Record<string, unknown>>;
    expect(Object.keys(paths['/habits'] ?? {})).toEqual(['post', 'get']);
  });

  it('declares bearer security on everything but the open routes', () => {
    const paths = document['paths'] as Record<string, Record<string, { security: unknown[] }>>;
    expect(paths['/health']?.['get']?.security).toEqual([]);
    expect(paths['/habits']?.['post']?.security).toEqual([{ bearerAuth: [] }]);
  });

  it('takes a title', () => {
    const named = openApiDocument({ version: '1.0.0', title: 'hitchline api' });
    expect((named['info'] as { title: string }).title).toBe('hitchline api');
  });

  it('refuses a version that is not one', () => {
    expect(() => openApiDocument({ version: 'one' })).toThrow(ValidationError);
  });
});
