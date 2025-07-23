import { ROUTES, type RouteDoc } from './manifest.js';
import { CATALOGUE, catalogueMarkdown } from './catalogue.js';

/**
 * The api reference, written from the same manifest the server registers and
 * the same catalogue it answers with. Documentation that is generated from the
 * thing it describes cannot drift from it, which is the only kind that stays
 * true a year later.
 */
export interface DocumentMeta {
  readonly version: string;
  readonly baseUrl?: string;
}

function scopeNote(route: RouteDoc): string {
  switch (route.scope) {
    case 'none':
      return 'no token needed';
    case 'read':
      return 'needs a token with the read scope';
    case 'write':
      return 'needs the write scope';
    case 'admin':
      return 'needs the admin scope';
  }
}

function parameterTable(route: RouteDoc): string[] {
  if (route.parameters.length === 0) return [];
  const lines = ['', '| parameter | in | required | what it is |', '| --- | --- | --- | --- |'];
  for (const parameter of route.parameters) {
    lines.push(
      `| ${parameter.name} | ${parameter.in} | ${parameter.required ? 'yes' : 'no'} | ${parameter.description} |`,
    );
  }
  return lines;
}

export function routeMarkdown(route: RouteDoc): string {
  const lines = [`### ${route.method} ${route.path}`, '', route.summary, '', scopeNote(route)];
  lines.push(...parameterTable(route));
  return lines.join('\n');
}

export function apiMarkdown(meta: DocumentMeta): string {
  const base = meta.baseUrl ?? 'http://127.0.0.1:8417';
  const lines = [
    `# hitchline api ${meta.version}`,
    '',
    'Every response is json. Errors carry a code, a message and a list of the fields',
    'that were wrong, and the code is the part worth branching on.',
    '',
    `Base url in a default install: ${base}`,
    '',
    '## Routes',
    '',
  ];

  for (const route of ROUTES) {
    lines.push(routeMarkdown(route), '');
  }

  lines.push('## Errors', '');
  lines.push(catalogueMarkdown().trimEnd(), '');
  lines.push(
    `There are ${String(CATALOGUE.length)} codes in all. Anything not on that list is a bug.`,
    '',
  );

  return `${lines.join('\n').trimEnd()}\n`;
}

export function tableOfContents(): string {
  return ROUTES.map((route) => `- ${route.method} ${route.path}`).join('\n');
}
