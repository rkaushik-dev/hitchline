import { describe, expect, it } from 'vitest';
import { LATENCY_BOUNDS, createMetrics } from '../../../src/http/metrics.js';
import { ValidationError } from '../../../src/domain/errors.js';

describe('counters', () => {
  it('counts up from zero', () => {
    const metrics = createMetrics();
    const requests = metrics.counter('http_requests_total', 'requests served');
    expect(requests.read()).toBe(0);
    requests.add();
    requests.add(2);
    expect(requests.read()).toBe(3);
  });

  it('keeps label sets apart', () => {
    const metrics = createMetrics();
    const requests = metrics.counter('http_requests_total', 'requests served');
    requests.add(1, { status: '200' });
    requests.add(1, { status: '404' });
    expect(requests.read({ status: '200' })).toBe(1);
    expect(requests.read({ status: '404' })).toBe(1);
    expect(requests.read()).toBe(0);
  });

  it('does not care what order labels are given in', () => {
    const metrics = createMetrics();
    const requests = metrics.counter('http_requests_total', 'requests served');
    requests.add(1, { method: 'GET', status: '200' });
    expect(requests.read({ status: '200', method: 'GET' })).toBe(1);
  });

  it('hands back the same counter for the same name', () => {
    const metrics = createMetrics();
    metrics.counter('http_requests_total', 'requests served').add(2);
    expect(metrics.counter('http_requests_total', 'requests served').read()).toBe(2);
  });

  it('refuses a name that is not a metric name', () => {
    const metrics = createMetrics();
    expect(() => metrics.counter('HTTP Requests', 'no')).toThrow(ValidationError);
  });

  it('refuses to reuse a name for a different kind', () => {
    const metrics = createMetrics();
    metrics.counter('http_requests_total', 'requests served');
    expect(() => metrics.histogram('http_requests_total', 'no', [1])).toThrow(/already a counter/);
  });
});

describe('histograms', () => {
  it('counts and sums what it sees', () => {
    const metrics = createMetrics();
    const latency = metrics.histogram('http_latency_ms', 'how long', [10, 100]);
    latency.observe(5);
    latency.observe(50);
    latency.observe(500);
    expect(latency.count()).toBe(3);
    expect(latency.sum()).toBe(555);
  });

  it('renders cumulative buckets', () => {
    const metrics = createMetrics();
    const latency = metrics.histogram('http_latency_ms', 'how long', [10, 100]);
    latency.observe(5);
    latency.observe(50);
    latency.observe(500);
    const text = metrics.render();
    expect(text).toContain('http_latency_ms_bucket{le="10"} 1');
    expect(text).toContain('http_latency_ms_bucket{le="100"} 2');
    expect(text).toContain('http_latency_ms_bucket{le="+Inf"} 3');
    expect(text).toContain('http_latency_ms_count 3');
    expect(text).toContain('http_latency_ms_sum 555');
  });

  it('keeps labels on the buckets', () => {
    const metrics = createMetrics();
    const latency = metrics.histogram('http_latency_ms', 'how long', [10]);
    latency.observe(5, { route: '/health' });
    expect(metrics.render()).toContain('http_latency_ms_bucket{route="/health",le="10"} 1');
  });

  it('sorts the bounds it was given', () => {
    const metrics = createMetrics();
    const latency = metrics.histogram('http_latency_ms', 'how long', [100, 10]);
    latency.observe(50);
    const lines = metrics.render().split('\n');
    expect(lines[2]).toContain('le="10"');
    expect(lines[3]).toContain('le="100"');
  });
});

describe('rendering', () => {
  it('writes nothing for an empty registry', () => {
    expect(createMetrics().render()).toBe('');
  });

  it('writes a help and a type line for each metric', () => {
    const metrics = createMetrics();
    metrics.counter('http_requests_total', 'requests served').add();
    const lines = metrics.render().split('\n');
    expect(lines[0]).toBe('# HELP http_requests_total requests served');
    expect(lines[1]).toBe('# TYPE http_requests_total counter');
    expect(lines[2]).toBe('http_requests_total 1');
  });

  it('sorts metrics by name', () => {
    const metrics = createMetrics();
    metrics.counter('zeta_total', 'z').add();
    metrics.counter('alpha_total', 'a').add();
    expect(metrics.render().indexOf('alpha_total')).toBeLessThan(
      metrics.render().indexOf('zeta_total'),
    );
  });

  it('escapes a label value with a quote in it', () => {
    const metrics = createMetrics();
    metrics.counter('http_requests_total', 'requests served').add(1, { route: 'a"b' });
    expect(metrics.render()).toContain('route="a\\"b"');
  });

  it('offers latency bounds that suit a small api', () => {
    expect(LATENCY_BOUNDS[0]).toBe(5);
    expect(LATENCY_BOUNDS.at(-1)).toBe(1_000);
  });
});
