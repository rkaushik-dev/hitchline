import type { FastifyInstance } from 'fastify';
import type { Ports } from '../../app/ports.js';
import { registerAdherenceRoutes } from './adherence.js';
import { registerCheckInRoutes } from './checkins.js';
import { registerCompareRoutes } from './compare.js';
import { registerCrewRoutes } from './crews.js';
import { registerHabitRoutes } from './habits.js';
import { registerNudgeRoutes } from './nudges.js';
import { registerRosterRoutes } from './roster.js';

/**
 * Every route the api serves, in one call. Order does not matter to fastify,
 * so they are listed the way somebody reading the api would meet them.
 */
export function registerRoutes(app: FastifyInstance, ports: Ports): void {
  registerHabitRoutes(app, ports);
  registerCheckInRoutes(app, ports);
  registerRosterRoutes(app, ports);
  registerAdherenceRoutes(app, ports);
  registerNudgeRoutes(app, ports);
  registerCrewRoutes(app, ports);
  registerCompareRoutes(app, ports);
}

export { registerAdherenceRoutes } from './adherence.js';
export { registerCheckInRoutes } from './checkins.js';
export { registerCompareRoutes } from './compare.js';
export { registerCrewRoutes } from './crews.js';
export { registerHabitRoutes } from './habits.js';
export { registerNudgeRoutes } from './nudges.js';
export { registerRosterRoutes } from './roster.js';
