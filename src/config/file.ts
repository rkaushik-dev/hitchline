import { readFile } from 'node:fs/promises';
import { ValidationError } from '../domain/errors.js';
import { readConfig, type Config } from './env.js';

/**
 * A configuration file, for the deployments where a dozen environment
 * variables in a unit file is worse than one json file somebody can read. The
 * environment still wins, because that is where a secret comes from and a
 * container orchestrator has no other way to reach in.
 */
export interface ConfigFile {
  readonly store?: string;
  readonly storePath?: string;
  readonly host?: string;
  readonly port?: number;
  readonly logLevel?: string;
  readonly defaultZone?: string;
  readonly allowedOrigins?: readonly string[];
}

const KEYS: readonly (keyof ConfigFile)[] = [
  'store',
  'storePath',
  'host',
  'port',
  'logLevel',
  'defaultZone',
  'allowedOrigins',
];

const TO_ENV: Readonly<Record<string, string>> = {
  store: 'HITCHLINE_STORE',
  storePath: 'HITCHLINE_STORE_PATH',
  host: 'HITCHLINE_HOST',
  port: 'HITCHLINE_PORT',
  logLevel: 'HITCHLINE_LOG_LEVEL',
  defaultZone: 'HITCHLINE_DEFAULT_ZONE',
};

export function parseConfigFile(text: string): ConfigFile {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw ValidationError.field('config', 'is not json');
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw ValidationError.field('config', 'must be a json object');
  }
  const source = parsed as Record<string, unknown>;
  for (const key of Object.keys(source)) {
    if (!(KEYS as readonly string[]).includes(key)) {
      throw ValidationError.field('config', `does not know the setting ${key}`);
    }
  }
  const file: Record<string, unknown> = {};
  for (const key of KEYS) {
    const value = source[key];
    if (value === undefined) continue;
    if (key === 'port' && typeof value !== 'number') {
      throw ValidationError.field('port', 'must be a number in the config file');
    }
    if (key === 'allowedOrigins' && !Array.isArray(value)) {
      throw ValidationError.field('allowedOrigins', 'must be a list in the config file');
    }
    file[key] = value;
  }
  return file as ConfigFile;
}

/** Turns the file into environment shaped values so one reader validates both. */
export function asEnvironment(file: ConfigFile): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  for (const [key, name] of Object.entries(TO_ENV)) {
    const value = (file as Record<string, unknown>)[key];
    if (typeof value !== 'string' && typeof value !== 'number') continue;
    env[name] = String(value);
  }
  return env;
}

export interface LoadedConfig {
  readonly config: Config;
  readonly allowedOrigins: readonly string[];
  readonly from: string | undefined;
}

/**
 * Reads the file when there is one, lets the environment overwrite anything it
 * names, and hands back one settled configuration.
 */
export async function loadConfig(
  path: string | undefined,
  env: NodeJS.ProcessEnv = process.env,
): Promise<LoadedConfig> {
  if (path === undefined) {
    return { config: readConfig(env), allowedOrigins: [], from: undefined };
  }
  let text: string;
  try {
    text = await readFile(path, 'utf8');
  } catch (cause) {
    if ((cause as NodeJS.ErrnoException).code === 'ENOENT') {
      throw ValidationError.field('config', `no configuration file at ${path}`);
    }
    throw cause;
  }
  const file = parseConfigFile(text);
  return {
    config: readConfig({ ...asEnvironment(file), ...env }),
    allowedOrigins: file.allowedOrigins ?? [],
    from: path,
  };
}
