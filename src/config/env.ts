import { ValidationError } from '../domain/errors.js';
import { isKnownZone } from '../domain/time/zone.js';

/**
 * Configuration, read once at startup. Everything is optional and has a
 * default that works on a laptop, because the first thing anybody does with
 * this is run it without reading the readme.
 */
export type StoreKind = 'memory' | 'file';
export type LogLevel = 'trace' | 'debug' | 'info' | 'warn' | 'error' | 'silent';

export interface ApiToken {
  readonly token: string;
  readonly memberId: string;
  readonly scopes: readonly string[];
}

export interface Config {
  readonly store: StoreKind;
  readonly storePath: string;
  readonly host: string;
  readonly port: number;
  readonly logLevel: LogLevel;
  readonly defaultZone: string;
  readonly tokens: readonly ApiToken[];
}

export const DEFAULTS = {
  store: 'file' as StoreKind,
  storePath: '.hitchline-data/hitchline.jsonl',
  host: '127.0.0.1',
  port: 8_417,
  logLevel: 'info' as LogLevel,
  defaultZone: 'UTC',
};

const LOG_LEVELS: readonly LogLevel[] = ['trace', 'debug', 'info', 'warn', 'error', 'silent'];
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;

function readPort(raw: string | undefined): number {
  if (raw === undefined || raw.trim().length === 0) return DEFAULTS.port;
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw ValidationError.field('HITCHLINE_PORT', `must be a port number, got ${raw}`);
  }
  return port;
}

function readLogLevel(raw: string | undefined): LogLevel {
  if (raw === undefined || raw.trim().length === 0) return DEFAULTS.logLevel;
  const level = raw.trim().toLowerCase();
  if (!(LOG_LEVELS as readonly string[]).includes(level)) {
    throw ValidationError.field('HITCHLINE_LOG_LEVEL', `is not a level: ${raw}`);
  }
  return level as LogLevel;
}

function readStore(raw: string | undefined): StoreKind {
  if (raw === undefined || raw.trim().length === 0) return DEFAULTS.store;
  const kind = raw.trim().toLowerCase();
  if (kind !== 'memory' && kind !== 'file') {
    throw ValidationError.field('HITCHLINE_STORE', `must be memory or file, got ${raw}`);
  }
  return kind;
}

/**
 * Tokens arrive as `token:memberId:scope|scope`, separated by commas. It is a
 * blunt format and it is meant to be: the real deployment puts a proxy in
 * front, and this exists so a crew can run the thing on a laptop.
 */
export function parseTokens(raw: string | undefined): ApiToken[] {
  if (raw === undefined || raw.trim().length === 0) return [];
  return raw
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
    .map((entry) => {
      const parts = entry.split(':');
      if (parts.length < 2) {
        throw ValidationError.field('HITCHLINE_TOKENS', `is not token:member:scopes, got ${entry}`);
      }
      const token = parts[0] ?? '';
      if (!TOKEN_PATTERN.test(token)) {
        throw ValidationError.field('HITCHLINE_TOKENS', 'has a token that is too short or odd');
      }
      const scopes = (parts[2] ?? 'read')
        .split('|')
        .map((scope) => scope.trim())
        .filter((scope) => scope.length > 0);
      return { token, memberId: parts[1] ?? '', scopes };
    });
}

export function readConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const zone = env['HITCHLINE_DEFAULT_ZONE'] ?? DEFAULTS.defaultZone;
  if (!isKnownZone(zone)) {
    throw ValidationError.field('HITCHLINE_DEFAULT_ZONE', `is not a timezone: ${zone}`);
  }
  const storePath = env['HITCHLINE_STORE_PATH'] ?? DEFAULTS.storePath;
  if (storePath.trim().length === 0) {
    throw ValidationError.field('HITCHLINE_STORE_PATH', 'must not be empty');
  }
  return {
    store: readStore(env['HITCHLINE_STORE']),
    storePath,
    host: env['HITCHLINE_HOST'] ?? DEFAULTS.host,
    port: readPort(env['HITCHLINE_PORT']),
    logLevel: readLogLevel(env['HITCHLINE_LOG_LEVEL']),
    defaultZone: zone,
    tokens: parseTokens(env['HITCHLINE_TOKENS']),
  };
}

export function describeConfig(config: Config): string {
  const where = config.store === 'file' ? config.storePath : 'memory';
  return `${config.host}:${String(config.port)} on ${where}, ${config.tokens.length} tokens`;
}
