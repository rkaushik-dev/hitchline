import { describe, expect, it } from 'vitest';
import {
  CATALOGUE,
  catalogueMarkdown,
  codesFor,
  entryFor,
  retryable,
} from '../../../src/http/catalogue.js';

describe('the catalogue', () => {
  it('covers every code the api answers with', () => {
    expect(CATALOGUE.map((entry) => entry.code)).toEqual([
      'validation_failed',
      'not_found',
      'conflict',
      'forbidden',
      'unsupported',
      'internal_error',
    ]);
  });

  it('carries the status each one maps to', () => {
    expect(entryFor('validation_failed')?.status).toBe(400);
    expect(entryFor('not_found')?.status).toBe(404);
    expect(entryFor('conflict')?.status).toBe(409);
    expect(entryFor('forbidden')?.status).toBe(403);
    expect(entryFor('unsupported')?.status).toBe(422);
    expect(entryFor('internal_error')?.status).toBe(500);
  });

  it('says something useful about each one', () => {
    for (const entry of CATALOGUE) {
      expect(entry.meaning.length).toBeGreaterThan(20);
      expect(entry.remedy.length).toBeGreaterThan(20);
      expect(entry.title.length).toBeGreaterThan(5);
    }
  });

  it('answers nothing for a code it does not know', () => {
    expect(entryFor('teapot')).toBeUndefined();
    expect(retryable('teapot')).toBe(false);
  });

  it('says which ones are worth retrying', () => {
    expect(retryable('conflict')).toBe(true);
    expect(retryable('internal_error')).toBe(true);
    expect(retryable('validation_failed')).toBe(false);
  });

  it('finds the codes behind a status', () => {
    expect(codesFor(404)).toEqual(['not_found']);
    expect(codesFor(418)).toEqual([]);
  });

  it('renders a table for the docs', () => {
    const table = catalogueMarkdown();
    const lines = table.split('\n');
    expect(lines[0]).toContain('| code |');
    expect(lines).toHaveLength(CATALOGUE.length + 3);
    expect(table).toContain('validation_failed');
  });
});
