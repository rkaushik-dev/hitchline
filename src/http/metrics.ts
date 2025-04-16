import { ValidationError } from '../domain/errors.js';

/**
 * Counters and buckets, rendered in the text format every scraper understands.
 * There is no client library here on purpose: the whole surface is four
 * numbers a crewing operation actually looks at, and a dependency that pulls
 * in a registry, a default metric set and a http server is not worth it.
 */
export type Labels = Readonly<Record<string, string>>;

export interface Counter {
  add(amount?: number, labels?: Labels): void;
  read(labels?: Labels): number;
}

export interface Histogram {
  observe(value: number, labels?: Labels): void;
  count(labels?: Labels): number;
  sum(labels?: Labels): number;
}

export interface Metrics {
  counter(name: string, help: string): Counter;
  histogram(name: string, help: string, bounds: readonly number[]): Histogram;
  render(): string;
}

const NAME_PATTERN = /^[a-z][a-z0-9_]{2,63}$/;

function labelKey(labels: Labels): string {
  const names = Object.keys(labels).sort();
  if (names.length === 0) return '';
  return names.map((name) => `${name}="${escapeLabel(labels[name] ?? '')}"`).join(',');
}

function escapeLabel(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

interface Series {
  readonly name: string;
  readonly help: string;
  readonly kind: 'counter' | 'histogram';
  readonly bounds: readonly number[];
  readonly counts: Map<string, number>;
  readonly sums: Map<string, number>;
  readonly buckets: Map<string, number[]>;
}

export function createMetrics(): Metrics {
  const series = new Map<string, Series>();

  function define(
    name: string,
    help: string,
    kind: Series['kind'],
    bounds: readonly number[],
  ): Series {
    if (!NAME_PATTERN.test(name)) {
      throw ValidationError.field('name', `is not a usable metric name: ${name}`);
    }
    const existing = series.get(name);
    if (existing !== undefined) {
      if (existing.kind !== kind) {
        throw ValidationError.field('name', `${name} is already a ${existing.kind}`);
      }
      return existing;
    }
    const created: Series = {
      name,
      help,
      kind,
      bounds: [...bounds].sort((left, right) => left - right),
      counts: new Map(),
      sums: new Map(),
      buckets: new Map(),
    };
    series.set(name, created);
    return created;
  }

  return {
    counter(name: string, help: string): Counter {
      const entry = define(name, help, 'counter', []);
      return {
        add(amount = 1, labels: Labels = {}) {
          const key = labelKey(labels);
          entry.counts.set(key, (entry.counts.get(key) ?? 0) + amount);
        },
        read(labels: Labels = {}) {
          return entry.counts.get(labelKey(labels)) ?? 0;
        },
      };
    },

    histogram(name: string, help: string, bounds: readonly number[]): Histogram {
      const entry = define(name, help, 'histogram', bounds);
      return {
        observe(value: number, labels: Labels = {}) {
          const key = labelKey(labels);
          const counts =
            entry.buckets.get(key) ?? new Array<number>(entry.bounds.length + 1).fill(0);
          let placed = false;
          for (const [index, bound] of entry.bounds.entries()) {
            if (value <= bound) {
              counts[index] = (counts[index] ?? 0) + 1;
              placed = true;
              break;
            }
          }
          if (!placed) counts[entry.bounds.length] = (counts[entry.bounds.length] ?? 0) + 1;
          entry.buckets.set(key, counts);
          entry.counts.set(key, (entry.counts.get(key) ?? 0) + 1);
          entry.sums.set(key, (entry.sums.get(key) ?? 0) + value);
        },
        count(labels: Labels = {}) {
          return entry.counts.get(labelKey(labels)) ?? 0;
        },
        sum(labels: Labels = {}) {
          return entry.sums.get(labelKey(labels)) ?? 0;
        },
      };
    },

    render(): string {
      const lines: string[] = [];
      for (const entry of [...series.values()].sort((a, b) => a.name.localeCompare(b.name))) {
        lines.push(`# HELP ${entry.name} ${entry.help}`);
        lines.push(`# TYPE ${entry.name} ${entry.kind}`);
        if (entry.kind === 'counter') {
          for (const [key, value] of entry.counts) {
            lines.push(`${entry.name}${key === '' ? '' : `{${key}}`} ${String(value)}`);
          }
          continue;
        }
        for (const [key, counts] of entry.buckets) {
          let running = 0;
          for (const [index, bound] of entry.bounds.entries()) {
            running += counts[index] ?? 0;
            const labels = [key, `le="${String(bound)}"`].filter((part) => part !== '').join(',');
            lines.push(`${entry.name}_bucket{${labels}} ${String(running)}`);
          }
          running += counts[entry.bounds.length] ?? 0;
          const infinite = [key, 'le="+Inf"'].filter((part) => part !== '').join(',');
          lines.push(`${entry.name}_bucket{${infinite}} ${String(running)}`);
          const suffix = key === '' ? '' : `{${key}}`;
          lines.push(`${entry.name}_sum${suffix} ${String(entry.sums.get(key) ?? 0)}`);
          lines.push(`${entry.name}_count${suffix} ${String(entry.counts.get(key) ?? 0)}`);
        }
      }
      return lines.length === 0 ? '' : `${lines.join('\n')}\n`;
    },
  };
}

export const LATENCY_BOUNDS: readonly number[] = [5, 10, 25, 50, 100, 250, 500, 1_000];
