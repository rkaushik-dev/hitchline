import { describe, expect, it } from 'vitest';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { apiMarkdown } from '../../src/http/docs.js';
import { API_VERSION } from '../../src/http/app.js';
import { ROUTES } from '../../src/http/manifest.js';
import { CATALOGUE } from '../../src/http/catalogue.js';

const path = join(process.cwd(), 'docs', 'api.md');

/**
 * The committed reference has to match what the generator writes. A route
 * added without regenerating it is the usual way documentation starts lying,
 * and this is the cheapest place to catch it.
 */
describe('the committed reference', () => {
  it('is exactly what the generator writes today', async () => {
    const committed = await readFile(path, 'utf8');
    expect(committed).toBe(apiMarkdown({ version: API_VERSION }));
  });

  it('names every route the manifest holds', async () => {
    const committed = await readFile(path, 'utf8');
    for (const route of ROUTES) {
      expect(committed).toContain(`### ${route.method} ${route.path}`);
    }
  });

  it('names every error code', async () => {
    const committed = await readFile(path, 'utf8');
    for (const entry of CATALOGUE) {
      expect(committed).toContain(entry.code);
    }
  });

  it('carries the version the build is at', async () => {
    const committed = await readFile(path, 'utf8');
    expect(committed.startsWith(`# hitchline api ${API_VERSION}`)).toBe(true);
  });
});
