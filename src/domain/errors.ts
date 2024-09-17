/**
 * Domain level failures. Nothing in `src/domain` is allowed to throw a bare
 * `Error`, because the HTTP and CLI layers both map failures back to the caller
 * by reading `code` rather than by matching on a message.
 */

export type DomainErrorCode =
  | 'validation_failed'
  | 'not_found'
  | 'conflict'
  | 'forbidden'
  | 'unsupported';

export interface DomainErrorDetail {
  /** Dotted path to the offending field, empty for whole object failures. */
  readonly path: string;
  readonly message: string;
}

export interface DomainErrorOptions {
  readonly details?: readonly DomainErrorDetail[];
  readonly cause?: unknown;
}

export class DomainError extends Error {
  readonly code: DomainErrorCode;
  readonly details: readonly DomainErrorDetail[];

  constructor(code: DomainErrorCode, message: string, options: DomainErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = new.target.name;
    this.code = code;
    this.details = options.details ?? [];
  }

  /** Flat, log friendly shape. Never includes the stack. */
  toJSON(): { code: DomainErrorCode; message: string; details: readonly DomainErrorDetail[] } {
    return { code: this.code, message: this.message, details: this.details };
  }
}

export class ValidationError extends DomainError {
  constructor(message: string, details: readonly DomainErrorDetail[] = []) {
    super('validation_failed', message, { details });
  }

  static field(path: string, message: string): ValidationError {
    return new ValidationError(`${path} ${message}`, [{ path, message }]);
  }
}

export class NotFoundError extends DomainError {
  readonly resource: string;
  readonly id: string;

  constructor(resource: string, id: string) {
    super('not_found', `${resource} ${id} does not exist`);
    this.resource = resource;
    this.id = id;
  }
}

export class ConflictError extends DomainError {
  constructor(message: string, details: readonly DomainErrorDetail[] = []) {
    super('conflict', message, { details });
  }
}

export class ForbiddenError extends DomainError {
  constructor(message: string) {
    super('forbidden', message);
  }
}

export class UnsupportedError extends DomainError {
  constructor(message: string) {
    super('unsupported', message);
  }
}

export function isDomainError(value: unknown): value is DomainError {
  return value instanceof DomainError;
}

/**
 * Collects the details of several validation failures into one error so a
 * caller sees every problem with a payload instead of only the first.
 */
export function combineValidation(
  message: string,
  errors: readonly ValidationError[],
): ValidationError | undefined {
  if (errors.length === 0) return undefined;
  const details = errors.flatMap((error) =>
    error.details.length > 0 ? [...error.details] : [{ path: '', message: error.message }],
  );
  return new ValidationError(message, details);
}
