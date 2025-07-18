import type { DomainErrorCode } from '../domain/errors.js';
import { statusFor } from './errors.js';
import {
  ConflictError,
  ForbiddenError,
  NotFoundError,
  UnsupportedError,
  ValidationError,
} from '../domain/errors.js';

/**
 * What each error code means and what the caller is supposed to do about it.
 * The api answers with a code rather than a sentence, so this is the only
 * place the sentence lives, and the docs and the /errors endpoint both read it
 * from here rather than repeating it.
 */
export interface CatalogueEntry {
  readonly code: DomainErrorCode | 'internal_error';
  readonly status: number;
  readonly title: string;
  readonly meaning: string;
  readonly remedy: string;
  readonly retryable: boolean;
}

export const CATALOGUE: readonly CatalogueEntry[] = [
  {
    code: 'validation_failed',
    status: statusFor(new ValidationError('x')),
    title: 'the request did not make sense',
    meaning: 'A field was missing, malformed, or outside the range the domain allows.',
    remedy: 'Read the details list, fix the named fields and send it again.',
    retryable: false,
  },
  {
    code: 'not_found',
    status: statusFor(new NotFoundError('habit', 'hab_1')),
    title: 'no such record',
    meaning: 'The id in the path or the body names something this store does not hold.',
    remedy: 'Check the id. An archived record is gone from every read.',
    retryable: false,
  },
  {
    code: 'conflict',
    status: statusFor(new ConflictError('x')),
    title: 'that would contradict something already stored',
    meaning:
      'Two roster entries covering one day, a member in two crews, or a second writer on the journal.',
    remedy: 'Read what is stored, decide which one is right, and send the one that wins.',
    retryable: true,
  },
  {
    code: 'forbidden',
    status: statusFor(new ForbiddenError('x')),
    title: 'that token may not do that',
    meaning:
      'The token is missing, has the wrong scope, or is acting for somebody other than itself.',
    remedy: 'Use a token with the scope the endpoint needs, or ask the person to share the habit.',
    retryable: false,
  },
  {
    code: 'unsupported',
    status: statusFor(new UnsupportedError('x')),
    title: 'this build cannot do that yet',
    meaning: 'A cadence, a backup version or a record shape this build does not understand.',
    remedy: 'Upgrade, or ask for the shape a supported build writes.',
    retryable: false,
  },
  {
    code: 'internal_error',
    status: 500,
    title: 'something went wrong on our side',
    meaning: 'An error nobody expected. The detail is in the log against the request id.',
    remedy: 'Retry once, then send the request id to whoever runs it.',
    retryable: true,
  },
];

export function entryFor(code: string): CatalogueEntry | undefined {
  return CATALOGUE.find((entry) => entry.code === code);
}

export function retryable(code: string): boolean {
  return entryFor(code)?.retryable ?? false;
}

export function codesFor(status: number): string[] {
  return CATALOGUE.filter((entry) => entry.status === status).map((entry) => entry.code);
}

export function catalogueMarkdown(): string {
  const lines = ['| code | status | what it means | what to do |', '| --- | --- | --- | --- |'];
  for (const entry of CATALOGUE) {
    lines.push(`| ${entry.code} | ${String(entry.status)} | ${entry.meaning} | ${entry.remedy} |`);
  }
  return `${lines.join('\n')}\n`;
}
