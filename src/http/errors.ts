import { DomainError, isDomainError, type DomainErrorCode } from '../domain/errors.js';

/**
 * The error body every route answers with. It is deliberately flat: the crew
 * app reads `code` and shows `message`, and the field list is there for a form
 * that wants to mark the box that was wrong.
 */
export interface ProblemBody {
  readonly code: DomainErrorCode | 'internal_error';
  readonly message: string;
  readonly details: readonly { path: string; message: string }[];
}

const STATUS: Record<DomainErrorCode, number> = {
  validation_failed: 400,
  not_found: 404,
  conflict: 409,
  forbidden: 403,
  unsupported: 422,
};

export function statusFor(error: unknown): number {
  if (isDomainError(error)) return STATUS[error.code];
  return 500;
}

export function problemFor(error: unknown): ProblemBody {
  if (isDomainError(error)) {
    return { code: error.code, message: error.message, details: [...error.details] };
  }
  return {
    code: 'internal_error',
    message: 'something went wrong on our side',
    details: [],
  };
}

/** True when the error is the caller's fault and should not page anybody. */
export function isCallerFault(error: unknown): boolean {
  return isDomainError(error) && error.code !== 'unsupported';
}

export function asDomainError(error: unknown): DomainError {
  if (isDomainError(error)) return error;
  const message = error instanceof Error ? error.message : String(error);
  return new DomainError('unsupported', message, { cause: error });
}
