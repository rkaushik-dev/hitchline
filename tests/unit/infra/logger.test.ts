import { describe, expect, it } from 'vitest';
import {
  REDACTED_PATHS,
  isEnabled,
  loggerFor,
  loggerOptions,
  requestLine,
} from '../../../src/infra/logger.js';
import { readConfig } from '../../../src/config/env.js';

const config = readConfig({});

describe('logger options', () => {
  it('carries the level and the service name', () => {
    const options = loggerOptions(config);
    expect(options.level).toBe('info');
    expect(options.base).toEqual({ service: 'hitchline', store: 'file' });
  });

  it('takes a service name', () => {
    expect(loggerOptions(config, 'hitchline-cli').base['service']).toBe('hitchline-cli');
  });

  it('redacts the authorization header and free text', () => {
    expect(REDACTED_PATHS).toContain('req.headers.authorization');
    expect(REDACTED_PATHS).toContain('body.note');
    expect(loggerOptions(config).redact).toEqual([...REDACTED_PATHS]);
  });

  it('hands fastify false when asked to be silent', () => {
    expect(loggerFor({ ...config, logLevel: 'silent' })).toBe(false);
    expect(loggerFor(config)).not.toBe(false);
  });
});

describe('level checks', () => {
  it('lets a level through at or above the threshold', () => {
    expect(isEnabled('warn', 'info')).toBe(true);
    expect(isEnabled('info', 'info')).toBe(true);
    expect(isEnabled('debug', 'info')).toBe(false);
  });

  it('lets nothing through when silent', () => {
    expect(isEnabled('silent', 'trace')).toBe(false);
  });
});

describe('request lines', () => {
  it('drops the query string and rounds the time', () => {
    expect(requestLine('get', '/members/mem_1/adherence?window=x', 200, 12.4)).toBe(
      'GET /members/mem_1/adherence 200 12ms',
    );
  });

  it('handles a url with no query', () => {
    expect(requestLine('post', '/check-ins', 201, 3)).toBe('POST /check-ins 201 3ms');
  });
});
