/**
 * The public surface. Everything a caller embedding hitchline needs is
 * re-exported here, and nothing else is: the http layer, the cli and the
 * storage adapters are wired through this file rather than reached into.
 *
 * The rule for adding to it is that the type or function has to be one an
 * embedder would name. Helpers the domain uses internally stay where they are.
 */

// The building blocks of a rotation.
export {
  parseRotationPattern,
  formatRotationPattern,
  rotationPattern,
  cycleLength,
  dutyFraction,
  type DayRole,
  type RotationPattern,
} from './domain/rotation/pattern.js';
export {
  rotationAssignment,
  hitchRange,
  hitchRangeOn,
  roleOn,
  type RotationAssignment,
} from './domain/rotation/assignment.js';
export { roster, roleFor, effectiveRoleOn, type Roster } from './domain/rotation/roster.js';
export { rosterOverride, type RosterOverride } from './domain/rotation/override.js';
export { zoneOn, rotationDayAt, type RotationDay } from './domain/rotation/zoning.js';

// Habits and what they ask for.
export { habit, isRunningOn, type Habit, type HabitStatus } from './domain/habit/habit.js';
export { parseCadence, formatCadence, type Cadence } from './domain/habit/cadence.js';
export { parseTarget, formatTarget, meetsTarget, type HabitTarget } from './domain/habit/target.js';
export { historyOf, revise, asOf, type HabitHistory } from './domain/habit/revision.js';
export { library, findTemplate, STANDARD_TEMPLATES } from './domain/habit/template.js';
export { expandHabit, expandAll, type Expansion } from './domain/schedule/expand.js';
export { obligation, obligationKey, type Obligation } from './domain/schedule/obligation.js';

// Check ins and how they are scored.
export { checkIn, voidCheckIn, type CheckIn } from './domain/checkin/checkin.js';
export { resolveCheckIn, resolveAll, type ResolvedCheckIn } from './domain/checkin/resolve.js';
export { chainOf, verifyChain } from './domain/checkin/integrity.js';
export { evaluateAll, adherenceRate, type ObligationResult } from './domain/adherence/match.js';
export { computeStreak, type StreakSummary } from './domain/adherence/streak.js';
export { computeFrozenStreak, freezeBudget } from './domain/adherence/freeze.js';
export { shield, isShielded, type Shield } from './domain/adherence/shield.js';
export { quota, readQuota, type Quota } from './domain/adherence/quota.js';
export { summarise, summariseByHitch, type PeriodSummary } from './domain/adherence/summary.js';
export { byRole, byHourOfDay, trendOf, type Trend } from './domain/adherence/analytics.js';

// Time, which every one of the above is measured on.
export {
  parseCivilDate,
  formatCivilDate,
  addDays,
  daysBetween,
  type CivilDate,
} from './domain/time/civil.js';
export { parseDateRange, formatDateRange, type DateRange } from './domain/time/range.js';
export { parseInstant, formatInstant, civilDateIn, type Instant } from './domain/time/zone.js';

// Errors, ids and the ports the application layer is built on.
export {
  DomainError,
  ValidationError,
  NotFoundError,
  ConflictError,
  ForbiddenError,
  isDomainError,
} from './domain/errors.js';
export { crewMemberId, habitId, checkInId, crewId, mintId } from './domain/ids.js';
export type { Clock, IdSource, Ports } from './app/ports.js';

// The application layer.
export { habitsService } from './app/habits.service.js';
export { checkInsService } from './app/checkins.service.js';
export { rosterService } from './app/roster.service.js';
export { adherenceService, type AdherenceReport } from './app/adherence.service.js';
export { nudgesService } from './app/nudges.service.js';
export { crewService } from './app/crew.service.js';
export { compareService } from './app/compare.service.js';
export { searchService } from './app/search.service.js';
export { importService } from './app/import.service.js';
export { rotaImportService } from './app/rota-import.service.js';
export { memberLoader, type LoadedMember } from './app/loader.js';
export { createBus, domainEvent, type DomainEvent } from './app/events.js';
export { createAuditLog, auditEverything, type AuditLog } from './app/audit.js';
export { digest, subjectLine } from './app/digest.js';
export { seedDemo, type SeedReport } from './app/seed.js';
export { planRetention, retentionPolicy } from './app/retention.js';

// Infrastructure and the surfaces on top of it.
export { memoryStore, type MemoryStore } from './infra/store/memory.js';
export { fileStore, type FileStore } from './infra/store/file.js';
export { openStore, closeStore, type OpenStore } from './infra/store/open.js';
export { systemClock, fixedClock } from './infra/clock.js';
export { randomIds, sequentialIds } from './infra/id.js';
export { readConfig, type Config } from './config/env.js';
export { loadConfig } from './config/file.js';
export { buildApp, buildAppWithMetrics, API_VERSION } from './http/app.js';
export { buildProgram } from './cli/main.js';
