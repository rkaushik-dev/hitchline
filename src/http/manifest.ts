import { ValidationError } from '../domain/errors.js';

/**
 * What the api serves, written down. It is kept by hand rather than generated
 * from the router, because the useful half of an api description is the prose
 * about what a window means and no generator writes that.
 */
export type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RouteParameter {
  readonly name: string;
  readonly in: 'path' | 'query';
  readonly required: boolean;
  readonly description: string;
}

export interface RouteDoc {
  readonly method: Method;
  readonly path: string;
  readonly summary: string;
  readonly scope: 'none' | 'read' | 'write' | 'admin';
  readonly parameters: readonly RouteParameter[];
}

const WINDOW: RouteParameter = {
  name: 'window',
  in: 'query',
  required: true,
  description: 'inclusive day range written as YYYY-MM-DD..YYYY-MM-DD',
};

const MEMBER: RouteParameter = {
  name: 'memberId',
  in: 'path',
  required: true,
  description: 'the crew member the record belongs to',
};

export const ROUTES: readonly RouteDoc[] = [
  {
    method: 'GET',
    path: '/health',
    summary: 'liveness and the clock reading',
    scope: 'none',
    parameters: [],
  },
  {
    method: 'GET',
    path: '/metrics',
    summary: 'counters in the text exposition format',
    scope: 'none',
    parameters: [],
  },
  {
    method: 'POST',
    path: '/habits',
    summary: 'give somebody a habit',
    scope: 'write',
    parameters: [],
  },
  {
    method: 'GET',
    path: '/habits',
    summary: 'list the habits of one member',
    scope: 'read',
    parameters: [
      { name: 'memberId', in: 'query', required: true, description: 'whose habits to list' },
      { name: 'limit', in: 'query', required: false, description: 'rows per page, up to 500' },
      { name: 'after', in: 'query', required: false, description: 'cursor from a previous page' },
    ],
  },
  {
    method: 'GET',
    path: '/habits/:habitId',
    summary: 'read one habit',
    scope: 'read',
    parameters: [],
  },
  {
    method: 'PATCH',
    path: '/habits/:habitId',
    summary: 'change a habit',
    scope: 'write',
    parameters: [],
  },
  {
    method: 'POST',
    path: '/check-ins',
    summary: 'record that somebody did the thing',
    scope: 'write',
    parameters: [],
  },
  {
    method: 'GET',
    path: '/members/:memberId/check-ins',
    summary: 'list check ins with the rotation day each landed on',
    scope: 'read',
    parameters: [MEMBER, { ...WINDOW, required: false }],
  },
  {
    method: 'POST',
    path: '/check-ins/:checkInId/void',
    summary: 'take a check in out of the count',
    scope: 'write',
    parameters: [],
  },
  {
    method: 'PATCH',
    path: '/check-ins/:checkInId',
    summary: 'amend a reading',
    scope: 'write',
    parameters: [],
  },
  {
    method: 'GET',
    path: '/members/:memberId/roster',
    summary: 'the role and the clock for each day',
    scope: 'read',
    parameters: [MEMBER, WINDOW],
  },
  {
    method: 'GET',
    path: '/members/:memberId/adherence',
    summary: 'how a window went, by habit and by hitch',
    scope: 'read',
    parameters: [MEMBER, WINDOW],
  },
  {
    method: 'GET',
    path: '/members/:memberId/nudges',
    summary: 'what the planner would send',
    scope: 'read',
    parameters: [MEMBER, WINDOW],
  },
  {
    method: 'POST',
    path: '/members/:memberId/sync',
    summary: 'take a batch a handset queued while it had no signal',
    scope: 'write',
    parameters: [MEMBER],
  },
  {
    method: 'GET',
    path: '/audit',
    summary: 'what anybody did, newest first',
    scope: 'admin',
    parameters: [
      { ...WINDOW, required: false },
      { name: 'memberId', in: 'query', required: false, description: 'narrow to one person' },
      { name: 'zone', in: 'query', required: false, description: 'zone the window is read in' },
    ],
  },
  { method: 'POST', path: '/crews', summary: 'start a crew', scope: 'admin', parameters: [] },
  {
    method: 'GET',
    path: '/crews/:crewId/comparison',
    summary: 'the crew side by side, minus anything not shared',
    scope: 'read',
    parameters: [
      { name: 'crewId', in: 'path', required: true, description: 'the crew to read' },
      WINDOW,
      { name: 'on', in: 'query', required: true, description: 'the day to read membership as' },
    ],
  },
];

export function routesByScope(scope: RouteDoc['scope']): RouteDoc[] {
  return ROUTES.filter((route) => route.scope === scope);
}

export function findRoute(method: Method, path: string): RouteDoc | undefined {
  return ROUTES.find((route) => route.method === method && route.path === path);
}

function openApiPath(path: string): string {
  return path.replace(/:([A-Za-z]+)/g, '{$1}');
}

export interface DocumentOptions {
  readonly version: string;
  readonly title?: string;
}

/** Renders the manifest as an openapi document, which is what tooling wants. */
export function openApiDocument(options: DocumentOptions): Record<string, unknown> {
  if (!/^\d+\.\d+\.\d+$/.test(options.version)) {
    throw ValidationError.field('version', `must look like 1.2.3, got ${options.version}`);
  }
  const paths: Record<string, Record<string, unknown>> = {};
  for (const route of ROUTES) {
    const key = openApiPath(route.path);
    const entry = paths[key] ?? {};
    entry[route.method.toLowerCase()] = {
      summary: route.summary,
      security: route.scope === 'none' ? [] : [{ bearerAuth: [] }],
      parameters: route.parameters.map((parameter) => ({
        name: parameter.name,
        in: parameter.in,
        required: parameter.required,
        description: parameter.description,
        schema: { type: 'string' },
      })),
      responses: {
        '200': { description: 'the record or records asked for' },
        '400': { description: 'the request did not make sense' },
        '403': { description: 'the token may not do that' },
      },
    };
    paths[key] = entry;
  }

  return {
    openapi: '3.1.0',
    info: { title: options.title ?? 'hitchline', version: options.version },
    components: {
      securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer' } },
    },
    paths,
  };
}
