import type { FastifyRequest } from 'fastify';
import { ValidationError } from '../domain/errors.js';
import { parseCivilDate, type CivilDate } from '../domain/time/civil.js';
import { parseDateRange, type DateRange } from '../domain/time/range.js';
import {
  crewId,
  crewMemberId,
  habitId,
  type CrewId,
  type CrewMemberId,
  type HabitId,
} from '../domain/ids.js';

/**
 * Reading the bits of a request every route needs. Six routes were each
 * checking for a window and saying something slightly different when it was
 * missing, which is how an api ends up with three spellings of one error.
 */
function stringOf(source: unknown, name: string): string | undefined {
  const value = (source as Record<string, unknown> | undefined)?.[name];
  return typeof value === 'string' ? value : undefined;
}

export function windowOf(request: FastifyRequest, what = 'read this'): DateRange {
  return parseDateRange(windowStringOf(request, what));
}

export function optionalWindowOf(request: FastifyRequest): DateRange | undefined {
  const raw = stringOf(request.query, 'window');
  return raw === undefined ? undefined : parseDateRange(raw);
}

/** The raw window, for the services that take a string and parse it themselves. */
export function windowStringOf(request: FastifyRequest, what = 'read this'): string {
  const raw = stringOf(request.query, 'window');
  if (raw === undefined) {
    throw ValidationError.field('window', `is needed to ${what}`);
  }
  return raw;
}

export function dateOf(request: FastifyRequest, name = 'on', what = 'read this'): CivilDate {
  return parseCivilDate(rawDateOf(request, name, what));
}

export function rawDateOf(request: FastifyRequest, name = 'on', what = 'read this'): string {
  const raw = stringOf(request.query, name);
  if (raw === undefined) {
    throw ValidationError.field(name, `is needed to ${what}`);
  }
  return raw;
}

export function memberOf(request: FastifyRequest, name = 'memberId'): CrewMemberId {
  const params = request.params as Record<string, string | undefined>;
  return crewMemberId(params[name] ?? '');
}

export function habitOf(request: FastifyRequest, name = 'habitId'): HabitId {
  const params = request.params as Record<string, string | undefined>;
  return habitId(params[name] ?? '');
}

export function crewOf(request: FastifyRequest, name = 'crewId'): CrewId {
  const params = request.params as Record<string, string | undefined>;
  return crewId(params[name] ?? '');
}

export function queryMemberOf(request: FastifyRequest, what = 'list these'): CrewMemberId {
  const raw = stringOf(request.query, 'memberId');
  if (raw === undefined) {
    throw ValidationError.field('memberId', `is needed to ${what}`);
  }
  return crewMemberId(raw);
}
