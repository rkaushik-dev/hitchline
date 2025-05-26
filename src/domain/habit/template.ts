import { ConflictError, NotFoundError, ValidationError } from '../errors.js';
import { formatCadence, parseCadence, type Cadence } from './cadence.js';
import { formatTarget, parseTarget, type HabitTarget } from './target.js';

/**
 * The habits an operator hands out rather than inventing each time. A rig has
 * a standing set: the safety read through, the rest hours, the exercise the
 * medic asks for. Templates keep those written down once so two crews are
 * measured against the same thing.
 */
export interface HabitTemplate {
  readonly key: string;
  readonly name: string;
  readonly cadence: Cadence;
  readonly target: HabitTarget | undefined;
  readonly tags: readonly string[];
  readonly graceDays: number;
  readonly note: string;
}

export interface TemplateInput {
  readonly key: string;
  readonly name: string;
  readonly cadence: string;
  readonly target?: string;
  readonly tags?: readonly string[];
  readonly graceDays?: number;
  readonly note?: string;
}

const KEY_PATTERN = /^[a-z][a-z0-9-]{2,39}$/;

export function habitTemplate(input: TemplateInput): HabitTemplate {
  if (!KEY_PATTERN.test(input.key)) {
    throw ValidationError.field('key', `must be a short lower case name, got ${input.key}`);
  }
  const graceDays = input.graceDays ?? 0;
  if (!Number.isInteger(graceDays) || graceDays < 0 || graceDays > 7) {
    throw ValidationError.field('graceDays', 'must be between 0 and 7');
  }
  return {
    key: input.key,
    name: input.name.trim(),
    cadence: parseCadence(input.cadence),
    target: input.target === undefined ? undefined : parseTarget(input.target),
    tags: [...new Set((input.tags ?? []).map((tag) => tag.trim().toLowerCase()))].sort(),
    graceDays,
    note: (input.note ?? '').trim(),
  };
}

/** The set that ships with the tool, which an operator can add to. */
export const STANDARD_TEMPLATES: readonly HabitTemplate[] = [
  habitTemplate({
    key: 'toolbox-talk',
    name: 'Read the toolbox talk',
    cadence: '1/onshift-day',
    tags: ['safety'],
    note: 'the sheet posted at the start of shift',
  }),
  habitTemplate({
    key: 'rest-hours',
    name: 'Log the rest hours',
    cadence: '1/day',
    target: 'at least 11 count',
    tags: ['rest', 'compliance'],
    graceDays: 1,
    note: 'hours away from work in the day, as the rest rules count them',
  }),
  habitTemplate({
    key: 'gym-session',
    name: 'Twenty minutes in the gym',
    cadence: '3/hitch',
    target: 'at least 20 minutes',
    tags: ['fitness'],
  }),
  habitTemplate({
    key: 'call-home',
    name: 'Call home',
    cadence: '1/offshift-block',
    tags: ['wellbeing'],
  }),
  habitTemplate({
    key: 'kit-check',
    name: 'Check the survival suit',
    cadence: '1/onshift-block',
    tags: ['safety'],
    note: 'once per stretch offshore, not once a day',
  }),
];

export interface TemplateLibrary {
  readonly templates: readonly HabitTemplate[];
}

export function library(extra: readonly HabitTemplate[] = []): TemplateLibrary {
  const templates = [...STANDARD_TEMPLATES];
  for (const template of extra) {
    if (templates.some((held) => held.key === template.key)) {
      throw new ConflictError(`a template already uses the key ${template.key}`);
    }
    templates.push(template);
  }
  return { templates };
}

export function findTemplate(library: TemplateLibrary, key: string): HabitTemplate {
  const found = library.templates.find((template) => template.key === key);
  if (found === undefined) throw new NotFoundError('habit template', key);
  return found;
}

export function templatesTagged(library: TemplateLibrary, tag: string): HabitTemplate[] {
  const wanted = tag.trim().toLowerCase();
  return library.templates.filter((template) => template.tags.includes(wanted));
}

export function describeTemplate(template: HabitTemplate): string {
  const target = template.target === undefined ? '' : `, ${formatTarget(template.target)}`;
  return `${template.key}: ${template.name} ${formatCadence(template.cadence)}${target}`;
}
