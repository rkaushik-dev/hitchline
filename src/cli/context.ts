import { isDomainError } from '../domain/errors.js';
import type { Config } from '../config/env.js';
import type { Ports } from '../app/ports.js';

/**
 * What a command is handed. Output goes through the context rather than
 * straight to the console, so a test can read back what a command printed and
 * the same command body works under a pipe.
 */
export interface Context {
  readonly ports: Ports;
  readonly config: Config;
  out(line: string): void;
  fail(line: string): void;
}

export const EXIT_OK = 0;
export const EXIT_FAILED = 1;
export const EXIT_MISUSED = 2;

export function consoleContext(ports: Ports, config: Config): Context {
  return {
    ports,
    config,
    out: (line: string) => process.stdout.write(`${line}\n`),
    fail: (line: string) => process.stderr.write(`${line}\n`),
  };
}

export interface Recorder extends Context {
  readonly lines: string[];
  readonly errors: string[];
  text(): string;
  errorText(): string;
  clear(): void;
}

/** A context that keeps what was printed, which is how the cli is tested. */
export function recordingContext(ports: Ports, config: Config): Recorder {
  const lines: string[] = [];
  const errors: string[] = [];
  return {
    ports,
    config,
    lines,
    errors,
    out: (line: string) => lines.push(line),
    fail: (line: string) => errors.push(line),
    text: () => lines.join('\n'),
    errorText: () => errors.join('\n'),
    clear: () => {
      lines.length = 0;
      errors.length = 0;
    },
  };
}

/**
 * Runs a command body and turns a domain failure into a message and an exit
 * code. Anything that is not a domain failure is a bug, so it is rethrown with
 * its stack rather than dressed up as a usage error.
 */
export async function runCommand(context: Context, body: () => Promise<void>): Promise<number> {
  try {
    await body();
    return EXIT_OK;
  } catch (error) {
    if (!isDomainError(error)) throw error;
    context.fail(`${error.code}: ${error.message}`);
    for (const detail of error.details) {
      context.fail(`  ${detail.path === '' ? '-' : detail.path} ${detail.message}`);
    }
    return error.code === 'validation_failed' ? EXIT_MISUSED : EXIT_FAILED;
  }
}
