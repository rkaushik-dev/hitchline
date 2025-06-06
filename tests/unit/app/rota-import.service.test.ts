import { beforeEach, describe, expect, it } from 'vitest';
import {
  describeRota,
  rotaImportService,
  type RotaImportService,
} from '../../../src/app/rota-import.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewMemberId } from '../../../src/domain/ids.js';
import { formatRotationPattern } from '../../../src/domain/rotation/pattern.js';
import { aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
let store: MemoryStore;
let service: RotaImportService;

beforeEach(() => {
  store = memoryStore({ clock: fixedClock('2025-06-09T09:00:00Z'), ids: sequentialIds() });
  service = rotaImportService(store);
});

const header = 'id,name,home,site,pattern,anchor,phase';
const adaLine = `${ada},Ada Vasquez,Europe/Lisbon,Europe/Oslo,14/14,2025-01-06,0`;
const boLine = 'mem_FEDCBA9876543210,Bo Lindqvist,Europe/Stockholm,Europe/Oslo,14/14,2025-01-06,14';

describe('a clean rota', () => {
  it('adds everybody on it', async () => {
    const report = await service.rota([header, adaLine, boLine].join('\n'));
    expect(report).toEqual({ read: 2, added: 2, updated: 0, failures: [] });
    expect(await store.members.all()).toHaveLength(2);
  });

  it('reads the pattern and the phase', async () => {
    await service.rota([header, boLine].join('\n'));
    const bo = await store.members.byId(crewMemberId('mem_FEDCBA9876543210'));
    expect(formatRotationPattern(bo?.rotation.pattern ?? aMember().rotation.pattern)).toBe(
      '14on 14off',
    );
    expect(bo?.rotation.phase).toBe(14);
  });

  it('updates somebody already on the books', async () => {
    await store.members.save(aMember());
    const report = await service.rota([header, adaLine].join('\n'));
    expect(report).toMatchObject({ added: 0, updated: 1 });
  });

  it('keeps the day somebody joined when it updates them', async () => {
    await store.members.save(aMember({ joinedOn: '2023-05-01' }));
    await service.rota([header, `${ada},Ada Vasquez,,Europe/Oslo,2-2-3,2025-06-02,0`].join('\n'));
    expect((await store.members.byId(ada))?.joinedOn.year).toBe(2023);
  });

  it('falls back to the site zone when home is blank', async () => {
    await service.rota([header, `${ada},Ada Vasquez,,Europe/Oslo,14/14,2025-01-06,0`].join('\n'));
    expect((await store.members.byId(ada))?.homeZone).toBe('Europe/Oslo');
  });

  it('takes a default home zone', async () => {
    await service.rota([header, `${ada},Ada Vasquez,,Europe/Oslo,14/14,2025-01-06,0`].join('\n'), {
      defaultHomeZone: 'Europe/London',
    });
    expect((await store.members.byId(ada))?.homeZone).toBe('Europe/London');
  });
});

describe('lines that will not go in', () => {
  it('reports a pattern it cannot read and keeps the rest', async () => {
    const bad = `mem_1111111111111111,Cleo,Europe/London,Europe/Oslo,two weeks,2025-01-06,0`;
    const report = await service.rota([header, adaLine, bad, boLine].join('\n'));
    expect(report.added).toBe(2);
    expect(report.failures).toHaveLength(1);
    expect(report.failures[0]).toMatchObject({ line: 3, name: 'Cleo' });
  });

  it('reports a phase that is not a number', async () => {
    const bad = `${ada},Ada Vasquez,Europe/Lisbon,Europe/Oslo,14/14,2025-01-06,half`;
    const report = await service.rota([header, bad].join('\n'));
    expect(report.failures[0]?.message).toContain('whole number');
  });

  it('reports an id that is not an id', async () => {
    const bad = `nobody,Ada Vasquez,Europe/Lisbon,Europe/Oslo,14/14,2025-01-06,0`;
    expect((await service.rota([header, bad].join('\n'))).failures).toHaveLength(1);
  });

  it('reports a zone the platform does not know', async () => {
    const bad = `${ada},Ada Vasquez,Europe/Lisbon,North Sea/Alpha,14/14,2025-01-06,0`;
    expect((await service.rota([header, bad].join('\n'))).failures).toHaveLength(1);
  });
});

describe('a dry run', () => {
  it('checks every line and stores nothing', async () => {
    const report = await service.rota([header, adaLine, boLine].join('\n'), { dryRun: true });
    expect(report.added).toBe(2);
    expect(await store.members.all()).toEqual([]);
  });
});

describe('the file itself', () => {
  it('refuses one missing a column', async () => {
    await expect(service.rota('id,name\nmem_1,Ada\n')).rejects.toThrow(/missing the columns/);
  });

  it('reads a header only file as nothing to do', async () => {
    expect(await service.rota(`${header}\n`)).toMatchObject({ read: 0, added: 0 });
  });
});

describe('describing a run', () => {
  it('reads back in one line', () => {
    expect(describeRota({ read: 3, added: 2, updated: 1, failures: [] })).toBe(
      '3 read, 2 added, 1 updated, 0 refused',
    );
  });
});
