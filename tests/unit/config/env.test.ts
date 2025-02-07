import { afterEach, describe, expect, it } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  DEFAULTS,
  describeConfig,
  parseTokens,
  readConfig,
  type Config,
} from '../../../src/config/env.js';
import { closeStore, openStore } from '../../../src/infra/store/open.js';
import { ValidationError } from '../../../src/domain/errors.js';
import { aMember } from '../../support/fixtures.js';

const directories: string[] = [];

afterEach(async () => {
  for (const directory of directories.splice(0)) {
    await rm(directory, { recursive: true, force: true });
  }
});

async function temporaryPath(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'hitchline-config-'));
  directories.push(directory);
  return join(directory, 'store.jsonl');
}

describe('defaults', () => {
  it('reads a config from an empty environment', () => {
    const config = readConfig({});
    expect(config.store).toBe(DEFAULTS.store);
    expect(config.port).toBe(DEFAULTS.port);
    expect(config.logLevel).toBe('info');
    expect(config.tokens).toEqual([]);
  });

  it('describes itself in one line', () => {
    expect(describeConfig(readConfig({}))).toBe(
      `127.0.0.1:8417 on ${DEFAULTS.storePath}, 0 tokens`,
    );
  });
});

describe('reading the environment', () => {
  it('takes the store kind and path', () => {
    const config = readConfig({ HITCHLINE_STORE: 'memory', HITCHLINE_STORE_PATH: '/tmp/x.jsonl' });
    expect(config.store).toBe('memory');
    expect(config.storePath).toBe('/tmp/x.jsonl');
  });

  it('refuses a store kind it does not know', () => {
    expect(() => readConfig({ HITCHLINE_STORE: 'postgres' })).toThrow(/must be memory or file/);
  });

  it('refuses an empty store path', () => {
    expect(() => readConfig({ HITCHLINE_STORE_PATH: '  ' })).toThrow(/must not be empty/);
  });

  it('takes the host and port', () => {
    const config = readConfig({ HITCHLINE_HOST: '0.0.0.0', HITCHLINE_PORT: '9000' });
    expect(config.host).toBe('0.0.0.0');
    expect(config.port).toBe(9000);
  });

  it('refuses a port that is not one', () => {
    expect(() => readConfig({ HITCHLINE_PORT: 'eighty' })).toThrow(/must be a port number/);
    expect(() => readConfig({ HITCHLINE_PORT: '70000' })).toThrow(/must be a port number/);
  });

  it('takes a log level and refuses an invented one', () => {
    expect(readConfig({ HITCHLINE_LOG_LEVEL: 'DEBUG' }).logLevel).toBe('debug');
    expect(() => readConfig({ HITCHLINE_LOG_LEVEL: 'chatty' })).toThrow(/is not a level/);
  });

  it('checks the default zone against the platform', () => {
    expect(readConfig({ HITCHLINE_DEFAULT_ZONE: 'Europe/Oslo' }).defaultZone).toBe('Europe/Oslo');
    expect(() => readConfig({ HITCHLINE_DEFAULT_ZONE: 'North Sea/Alpha' })).toThrow(
      ValidationError,
    );
  });
});

describe('tokens', () => {
  it('reads one entry', () => {
    const tokens = parseTokens('abcdefghijklmnop:mem_0123456789ABCDEF:read|write');
    expect(tokens).toEqual([
      {
        token: 'abcdefghijklmnop',
        memberId: 'mem_0123456789ABCDEF',
        scopes: ['read', 'write'],
      },
    ]);
  });

  it('defaults the scope to read', () => {
    expect(parseTokens('abcdefghijklmnop:mem_1')[0]?.scopes).toEqual(['read']);
  });

  it('reads several separated by commas', () => {
    expect(parseTokens('abcdefghijklmnop:mem_1, qrstuvwxyz012345:mem_2')).toHaveLength(2);
  });

  it('reads nothing from an empty value', () => {
    expect(parseTokens(undefined)).toEqual([]);
    expect(parseTokens('  ')).toEqual([]);
  });

  it('refuses a short token', () => {
    expect(() => parseTokens('short:mem_1')).toThrow(/too short or odd/);
  });

  it('refuses an entry with no member', () => {
    expect(() => parseTokens('abcdefghijklmnop')).toThrow(/is not token:member:scopes/);
  });
});

describe('opening a store', () => {
  it('opens the memory one', async () => {
    const config: Config = { ...readConfig({}), store: 'memory' };
    const store = await openStore(config);
    await store.members.save(aMember());
    expect(await store.members.all()).toHaveLength(1);
    await closeStore(store);
  });

  it('opens the file one and writes to the path', async () => {
    const storePath = await temporaryPath();
    const store = await openStore({ ...readConfig({}), store: 'file', storePath });
    await store.members.save(aMember());
    await closeStore(store);

    const reopened = await openStore({ ...readConfig({}), store: 'file', storePath });
    expect(await reopened.members.all()).toHaveLength(1);
    await closeStore(reopened);
  });

  it('closes a store with nothing to close', async () => {
    const store = await openStore({ ...readConfig({}), store: 'memory' });
    await expect(closeStore(store)).resolves.toBeUndefined();
  });
});
