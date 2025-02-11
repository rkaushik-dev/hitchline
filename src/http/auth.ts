import type { FastifyInstance, FastifyRequest } from 'fastify';
import { ForbiddenError, ValidationError } from '../domain/errors.js';
import { crewMemberId, type CrewMemberId } from '../domain/ids.js';
import type { ApiToken } from '../config/env.js';

/**
 * Bearer tokens, checked against the list the process was started with. There
 * is no user table and no password anywhere in this codebase on purpose: the
 * operator already has an identity provider and this is the seam into it.
 */
export type Scope = 'read' | 'write' | 'admin';

export interface Principal {
  readonly memberId: CrewMemberId;
  readonly scopes: readonly Scope[];
}

const SCOPES: readonly Scope[] = ['read', 'write', 'admin'];

declare module 'fastify' {
  interface FastifyRequest {
    principal?: Principal;
  }
}

export function isScope(value: string): value is Scope {
  return (SCOPES as readonly string[]).includes(value);
}

export function principalOf(token: ApiToken): Principal {
  const scopes = token.scopes.filter(isScope);
  if (scopes.length === 0) {
    throw ValidationError.field('scopes', `names none this build knows: ${token.scopes.join('|')}`);
  }
  return { memberId: crewMemberId(token.memberId), scopes };
}

/** Reads `Authorization: Bearer <token>` and nothing else. */
export function readBearer(header: string | undefined): string | undefined {
  if (header === undefined) return undefined;
  const parts = header.split(' ');
  if (parts.length !== 2) return undefined;
  if ((parts[0] ?? '').toLowerCase() !== 'bearer') return undefined;
  const token = parts[1] ?? '';
  return token.length === 0 ? undefined : token;
}

export function findPrincipal(
  tokens: readonly ApiToken[],
  header: string | undefined,
): Principal | undefined {
  const presented = readBearer(header);
  if (presented === undefined) return undefined;
  const match = tokens.find((token) => token.token === presented);
  return match === undefined ? undefined : principalOf(match);
}

export function hasScope(principal: Principal, scope: Scope): boolean {
  return principal.scopes.includes(scope) || principal.scopes.includes('admin');
}

export function requireScope(request: FastifyRequest, scope: Scope): Principal {
  const principal = request.principal;
  if (principal === undefined) {
    throw new ForbiddenError('this endpoint needs a bearer token');
  }
  if (!hasScope(principal, scope)) {
    throw new ForbiddenError(`this endpoint needs the ${scope} scope`);
  }
  return principal;
}

/** Only an admin, or the person themselves, may act for a member. */
export function requireSelfOrAdmin(request: FastifyRequest, memberId: CrewMemberId): Principal {
  const principal = requireScope(request, 'read');
  if (principal.memberId === memberId) return principal;
  if (principal.scopes.includes('admin')) return principal;
  throw new ForbiddenError(`${principal.memberId} may not act for ${memberId}`);
}

export interface AuthOptions {
  readonly tokens: readonly ApiToken[];
}

export function registerAuth(app: FastifyInstance, options: AuthOptions): void {
  app.decorateRequest('principal', undefined);
  app.addHook('onRequest', (request, _reply, done) => {
    const header = request.headers.authorization;
    request.principal = findPrincipal(options.tokens, header);
    done();
  });
}
