import type { Config, LogLevel } from '../config/env.js';

/**
 * Logging options. The important part is the redaction list: a check in note
 * can hold anything a crew member typed, and a bearer token must never reach
 * a log line, so both are stripped before anything is written.
 */
export interface LoggerOptions {
  level: LogLevel;
  redact: string[];
  base: Record<string, string>;
}

export const REDACTED_PATHS: string[] = [
  'req.headers.authorization',
  'req.headers.cookie',
  'body.note',
  'body.idempotencyKey',
  'res.headers["set-cookie"]',
];

export function loggerOptions(config: Config, service = 'hitchline'): LoggerOptions {
  return {
    level: config.logLevel,
    redact: [...REDACTED_PATHS],
    base: { service, store: config.store },
  };
}

/** Fastify takes `false` rather than a silent level, which reads oddly here. */
export function loggerFor(config: Config): LoggerOptions | false {
  return config.logLevel === 'silent' ? false : loggerOptions(config);
}

export function isEnabled(level: LogLevel, threshold: LogLevel): boolean {
  const order: LogLevel[] = ['trace', 'debug', 'info', 'warn', 'error', 'silent'];
  if (level === 'silent') return false;
  return order.indexOf(level) >= order.indexOf(threshold);
}

/**
 * A one line summary of a request, used where a full log line would be noise.
 * Query strings are dropped because a window is not worth a line and an id in
 * a path is enough to find the request again.
 */
export function requestLine(method: string, url: string, status: number, ms: number): string {
  const path = url.split('?')[0] ?? url;
  return `${method.toUpperCase()} ${path} ${String(status)} ${String(Math.round(ms))}ms`;
}
