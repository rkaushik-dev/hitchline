import { describe, expect, it } from 'vitest';
import {
  STANDARD_TEMPLATES,
  describeTemplate,
  findTemplate,
  habitTemplate,
  library,
  templatesTagged,
} from '../../../../src/domain/habit/template.js';
import { formatCadence } from '../../../../src/domain/habit/cadence.js';
import { ConflictError, NotFoundError, ValidationError } from '../../../../src/domain/errors.js';

describe('building a template', () => {
  it('parses the cadence and the target', () => {
    const template = habitTemplate({
      key: 'gym',
      name: 'Gym',
      cadence: '3/hitch',
      target: 'at least 20 minutes',
    });
    expect(formatCadence(template.cadence)).toBe('3/hitch');
    expect(template.target?.amount).toBe(20);
  });

  it('tidies the tags', () => {
    const template = habitTemplate({
      key: 'gym',
      name: 'Gym',
      cadence: '3/hitch',
      tags: ['Fitness', 'fitness', ' health '],
    });
    expect(template.tags).toEqual(['fitness', 'health']);
  });

  it('refuses a key that is not a short lower case name', () => {
    expect(() => habitTemplate({ key: 'Gym Session', name: 'x', cadence: '1/day' })).toThrow(
      ValidationError,
    );
    expect(() => habitTemplate({ key: 'ab', name: 'x', cadence: '1/day' })).toThrow(/short lower/);
  });

  it('bounds the grace window', () => {
    expect(() =>
      habitTemplate({ key: 'gym', name: 'Gym', cadence: '1/day', graceDays: 9 }),
    ).toThrow(/between 0 and 7/);
  });

  it('passes a bad cadence straight back', () => {
    expect(() => habitTemplate({ key: 'gym', name: 'Gym', cadence: 'sometimes' })).toThrow(
      /must look like/,
    );
  });
});

describe('the standard set', () => {
  it('covers safety, rest, fitness and wellbeing', () => {
    const keys = STANDARD_TEMPLATES.map((template) => template.key);
    expect(keys).toEqual(['toolbox-talk', 'rest-hours', 'gym-session', 'call-home', 'kit-check']);
  });

  it('uses a block cadence where once a day would be wrong', () => {
    const kit = STANDARD_TEMPLATES.find((template) => template.key === 'kit-check');
    expect(formatCadence(kit?.cadence ?? STANDARD_TEMPLATES[0]!.cadence)).toBe('1/onshift-block');
  });

  it('gives the rest hours a day of grace', () => {
    const rest = STANDARD_TEMPLATES.find((template) => template.key === 'rest-hours');
    expect(rest?.graceDays).toBe(1);
  });
});

describe('a library', () => {
  it('holds the standard set on its own', () => {
    expect(library().templates).toHaveLength(STANDARD_TEMPLATES.length);
  });

  it('takes extra templates', () => {
    const extra = habitTemplate({ key: 'lifeboat-drill', name: 'Drill', cadence: '1/hitch' });
    expect(library([extra]).templates).toHaveLength(STANDARD_TEMPLATES.length + 1);
  });

  it('refuses an extra template that reuses a key', () => {
    const clash = habitTemplate({ key: 'gym-session', name: 'Gym', cadence: '1/day' });
    expect(() => library([clash])).toThrow(ConflictError);
  });

  it('finds one by key', () => {
    expect(findTemplate(library(), 'call-home').name).toBe('Call home');
  });

  it('complains about a key it does not hold', () => {
    expect(() => findTemplate(library(), 'nothing')).toThrow(NotFoundError);
  });

  it('filters by tag', () => {
    expect(templatesTagged(library(), 'safety').map((template) => template.key)).toEqual([
      'toolbox-talk',
      'kit-check',
    ]);
    expect(templatesTagged(library(), 'Safety')).toHaveLength(2);
    expect(templatesTagged(library(), 'nothing')).toEqual([]);
  });
});

describe('describing one', () => {
  it('reads back in one line', () => {
    expect(describeTemplate(findTemplate(library(), 'gym-session'))).toBe(
      'gym-session: Twenty minutes in the gym 3/hitch, at least 20 minutes',
    );
  });

  it('leaves the target out when there is none', () => {
    expect(describeTemplate(findTemplate(library(), 'call-home'))).toBe(
      'call-home: Call home 1/offshift-block',
    );
  });
});
