import { afterEach, describe, expect, it } from 'vitest';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { asEnvironment, loadConfig, parseConfigFile } from '../../../src/config/file.js';
import { ValidationError } from '../../../src/domain/errors.js';

const directories: string[] = [];

afterEach(async () => {
  for (const directory of directories.splice(0)) {
    await rm(directory, { recursive: true, force: true });
  }
});

async function fileWith(body: unknown): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'hitchline-config-file-'));
  directories.push(directory);
  const path = join(directory, 'hitchline.json');
  await writeFile(path, typeof body === 'string' ? body : JSON.stringify(body), 'utf8');
  return path;
}

describe('parsing', () => {
  it('reads the settings it knows', () => {
    const file = parseConfigFile('{"store":"memory","port":9000,"host":"0.0.0.0"}');
    expect(file).toEqual({ store: 'memory', port: 9000, host: '0.0.0.0' });
  });

  it('reads a list of origins', () => {
    const file = parseConfigFile('{"allowedOrigins":["https://crew.example"]}');
    expect(file.allowedOrigins).toEqual(['https://crew.example']);
  });

  it('refuses something that is not json', () => {
    expect(() => parseConfigFile('nope')).toThrow(/is not json/);
  });

  it('refuses a list or a bare value', () => {
    expect(() => parseConfigFile('[]')).toThrow(/must be a json object/);
    expect(() => parseConfigFile('"hello"')).toThrow(/must be a json object/);
  });

  it('refuses a setting it does not know', () => {
    expect(() => parseConfigFile('{"database":"postgres"}')).toThrow(/does not know the setting/);
  });

  it('refuses a port that is not a number', () => {
    expect(() => parseConfigFile('{"port":"9000"}')).toThrow(ValidationError);
  });

  it('refuses origins that are not a list', () => {
    expect(() => parseConfigFile('{"allowedOrigins":"all"}')).toThrow(/must be a list/);
  });
});

describe('turning a file into environment values', () => {
  it('names each setting the way the reader expects', () => {
    expect(asEnvironment({ store: 'memory', port: 9000 })).toEqual({
      HITCHLINE_STORE: 'memory',
      HITCHLINE_PORT: '9000',
    });
  });

  it('leaves out anything the file did not set', () => {
    expect(Object.keys(asEnvironment({}))).toEqual([]);
  });

  it('leaves the origins out, since they are not an environment setting', () => {
    expect(asEnvironment({ allowedOrigins: ['https://crew.example'] })).toEqual({});
  });
});

describe('loading', () => {
  it('reads the environment alone when there is no file', async () => {
    const loaded = await loadConfig(undefined, { HITCHLINE_STORE: 'memory' });
    expect(loaded.config.store).toBe('memory');
    expect(loaded.from).toBeUndefined();
  });

  it('reads a file and remembers where it came from', async () => {
    const path = await fileWith({ store: 'memory', port: 9100 });
    const loaded = await loadConfig(path, {});
    expect(loaded.config.port).toBe(9100);
    expect(loaded.from).toBe(path);
  });

  it('lets the environment overrule the file', async () => {
    const path = await fileWith({ store: 'memory', port: 9100 });
    const loaded = await loadConfig(path, { HITCHLINE_PORT: '9200' });
    expect(loaded.config.port).toBe(9200);
    expect(loaded.config.store).toBe('memory');
  });

  it('carries the origins through', async () => {
    const path = await fileWith({ allowedOrigins: ['https://crew.example'] });
    expect((await loadConfig(path, {})).allowedOrigins).toEqual(['https://crew.example']);
  });

  it('complains about a file that is not there', async () => {
    await expect(loadConfig('/nowhere/hitchline.json', {})).rejects.toThrow(
      /no configuration file at/,
    );
  });

  it('still validates what the file said', async () => {
    const path = await fileWith({ port: 99_999 });
    await expect(loadConfig(path, {})).rejects.toThrow(/must be a port number/);
  });
});

describe('the write limit in a file', () => {
  it('is read and turned into environment values', () => {
    expect(asEnvironment({ writesPerMinute: 600, burst: 90 })).toEqual({
      HITCHLINE_WRITES_PER_MINUTE: '600',
      HITCHLINE_BURST: '90',
    });
  });

  it('refuses one that is not a number', () => {
    expect(() => parseConfigFile('{"burst":"lots"}')).toThrow(/must be a number/);
  });

  it('reaches the config the server builds', async () => {
    const path = await fileWith({ writesPerMinute: 600, burst: 90 });
    const loaded = await loadConfig(path, {});
    expect(loaded.config.writesPerMinute).toBe(600);
    expect(loaded.config.burst).toBe(90);
  });
});
