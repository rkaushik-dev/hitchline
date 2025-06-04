import { ValidationError } from '../domain/errors.js';
import type { CrewMemberId } from '../domain/ids.js';
import { hasTag, type Habit, type HabitStatus } from '../domain/habit/habit.js';
import type { Ports } from './ports.js';

/**
 * Finding a habit again. A coordinator setting up a rig ends up with a few
 * hundred of them across a crew, and "which of these is the rest hours one"
 * is the question they ask, so the search is over names and tags rather than
 * over ids.
 */
export interface HabitQuery {
  readonly memberId?: CrewMemberId;
  readonly text?: string;
  readonly tags?: readonly string[];
  readonly status?: HabitStatus;
  /** Match every tag rather than any of them. */
  readonly allTags?: boolean;
}

export interface TagCount {
  readonly tag: string;
  readonly habits: number;
}

export interface SearchService {
  habits(query: HabitQuery): Promise<Habit[]>;
  tags(memberId?: CrewMemberId): Promise<TagCount[]>;
}

const TEXT_MAX = 80;

function normalise(text: string): string {
  return text.trim().toLowerCase();
}

function matchesText(habit: Habit, text: string): boolean {
  return normalise(habit.name).includes(text);
}

function matchesTags(habit: Habit, tags: readonly string[], all: boolean): boolean {
  if (tags.length === 0) return true;
  return all ? tags.every((tag) => hasTag(habit, tag)) : tags.some((tag) => hasTag(habit, tag));
}

export function searchService(ports: Ports): SearchService {
  async function candidates(memberId: CrewMemberId | undefined): Promise<Habit[]> {
    if (memberId !== undefined) return ports.habits.byMember(memberId);
    const found: Habit[] = [];
    for (const member of await ports.members.all()) {
      found.push(...(await ports.habits.byMember(member.id)));
    }
    return found;
  }

  return {
    async habits(query: HabitQuery): Promise<Habit[]> {
      const text = query.text === undefined ? undefined : normalise(query.text);
      if (text !== undefined && text.length > TEXT_MAX) {
        throw ValidationError.field('text', `must be at most ${TEXT_MAX} characters`);
      }
      const tags = (query.tags ?? []).map(normalise).filter((tag) => tag.length > 0);

      return (await candidates(query.memberId))
        .filter((habit) => (query.status === undefined ? true : habit.status === query.status))
        .filter((habit) => (text === undefined ? true : matchesText(habit, text)))
        .filter((habit) => matchesTags(habit, tags, query.allTags === true))
        .sort((left, right) => left.name.localeCompare(right.name));
    },

    async tags(memberId?: CrewMemberId): Promise<TagCount[]> {
      const counts = new Map<string, number>();
      for (const habit of await candidates(memberId)) {
        for (const tag of habit.tags) {
          counts.set(tag, (counts.get(tag) ?? 0) + 1);
        }
      }
      return [...counts.entries()]
        .map(([tag, habits]) => ({ tag, habits }))
        .sort((left, right) => right.habits - left.habits || left.tag.localeCompare(right.tag));
    },
  };
}
