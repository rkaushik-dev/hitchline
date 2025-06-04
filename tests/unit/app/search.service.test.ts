import { beforeEach, describe, expect, it } from 'vitest';
import { searchService, type SearchService } from '../../../src/app/search.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { moveHabitTo } from '../../../src/domain/habit/habit.js';
import { crewMemberId } from '../../../src/domain/ids.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
let store: MemoryStore;
let service: SearchService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-06-04T09:00:00Z'), ids: sequentialIds() });
  service = searchService(store);
  await store.members.save(aMember());
  await store.members.save(aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' }));
  await store.habits.save(
    aHabit({
      id: 'hab_0000000000000001',
      name: 'Twenty minutes on the treadmill',
      tags: ['fitness', 'cardio'],
    }),
  );
  await store.habits.save(
    aHabit({ id: 'hab_0000000000000002', name: 'Bank the sleep hours', tags: ['rest'] }),
  );
  await store.habits.save(
    aHabit({
      id: 'hab_0000000000000003',
      memberId: 'mem_FEDCBA9876543210',
      name: 'Read the toolbox talk',
      tags: ['safety'],
    }),
  );
});

describe('searching habits', () => {
  it('finds everything with no query at all', async () => {
    expect(await service.habits({})).toHaveLength(3);
  });

  it('narrows to one member', async () => {
    expect(await service.habits({ memberId: ada })).toHaveLength(2);
    expect(await service.habits({ memberId: bo })).toHaveLength(1);
  });

  it('matches part of a name, whatever the case', async () => {
    const found = await service.habits({ text: 'TREADMILL' });
    expect(found.map((habit) => habit.name)).toEqual(['Twenty minutes on the treadmill']);
  });

  it('matches a tag', async () => {
    expect(await service.habits({ tags: ['rest'] })).toHaveLength(1);
    expect(await service.habits({ tags: ['Fitness'] })).toHaveLength(1);
  });

  it('matches any tag by default and every tag when asked', async () => {
    expect(await service.habits({ tags: ['fitness', 'rest'] })).toHaveLength(2);
    expect(await service.habits({ tags: ['fitness', 'rest'], allTags: true })).toEqual([]);
    expect(await service.habits({ tags: ['fitness', 'cardio'], allTags: true })).toHaveLength(1);
  });

  it('narrows by status', async () => {
    const paused = moveHabitTo(aHabit({ id: 'hab_0000000000000001', tags: ['fitness'] }), 'paused');
    await store.habits.save(paused);
    expect(await service.habits({ status: 'paused' })).toHaveLength(1);
    expect(await service.habits({ status: 'active' })).toHaveLength(2);
  });

  it('combines the filters', async () => {
    const found = await service.habits({ memberId: ada, tags: ['fitness'], text: 'twenty' });
    expect(found).toHaveLength(1);
  });

  it('sorts by name', async () => {
    const found = await service.habits({ memberId: ada });
    expect(found.map((habit) => habit.name)).toEqual([
      'Bank the sleep hours',
      'Twenty minutes on the treadmill',
    ]);
  });

  it('finds nothing for text that matches nothing', async () => {
    expect(await service.habits({ text: 'kayaking' })).toEqual([]);
  });

  it('refuses an absurdly long search', async () => {
    await expect(service.habits({ text: 'x'.repeat(81) })).rejects.toThrow(/at most 80/);
  });
});

describe('counting tags', () => {
  it('counts every tag in use', async () => {
    expect(await service.tags()).toEqual([
      { tag: 'cardio', habits: 1 },
      { tag: 'fitness', habits: 1 },
      { tag: 'rest', habits: 1 },
      { tag: 'safety', habits: 1 },
    ]);
  });

  it('narrows to one member', async () => {
    expect((await service.tags(bo)).map((entry) => entry.tag)).toEqual(['safety']);
  });

  it('puts the busiest tag first', async () => {
    await store.habits.save(
      aHabit({ id: 'hab_0000000000000004', name: 'Row the ergometer', tags: ['fitness'] }),
    );
    expect((await service.tags())[0]).toEqual({ tag: 'fitness', habits: 2 });
  });
});
