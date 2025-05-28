import { z } from 'zod';
import { ValidationError, type DomainErrorDetail } from '../domain/errors.js';

/**
 * Shapes for anything arriving over http. The schemas only check the shape,
 * never the meaning: a cadence is a string here and the domain decides whether
 * it is a cadence, so there is one place that knows what "3/hitch" means.
 */
const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'must be written as YYYY-MM-DD');

const dateWindow = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2}$/, 'must be written as YYYY-MM-DD..YYYY-MM-DD');

const idOf = (prefix: string) =>
  z.string().regex(new RegExp(`^${prefix}_[0-9A-HJKMNP-TV-Z]{16}$`), `must be a ${prefix} id`);

export const memberIdSchema = idOf('mem');
export const habitIdSchema = idOf('hab');
export const checkInIdSchema = idOf('chk');
export const crewIdSchema = idOf('crw');

export const createHabitSchema = z.object({
  memberId: memberIdSchema,
  name: z.string().min(3).max(100),
  cadence: z.string().min(1).max(40),
  target: z.string().min(1).max(60).optional(),
  startsOn: isoDate.optional(),
  endsOn: isoDate.optional(),
  tags: z.array(z.string().min(1).max(24)).max(8).optional(),
  graceDays: z.number().int().min(0).max(7).optional(),
});

export const applyTemplateSchema = z.object({
  memberId: memberIdSchema,
  key: z.string().regex(/^[a-z][a-z0-9-]{2,39}$/, 'must be a template key'),
  startsOn: isoDate.optional(),
  endsOn: isoDate.optional(),
});

export const updateHabitSchema = z
  .object({
    name: z.string().min(3).max(100).optional(),
    cadence: z.string().min(1).max(40).optional(),
    target: z.string().min(1).max(60).nullable().optional(),
    tags: z.array(z.string().min(1).max(24)).max(8).optional(),
    status: z.enum(['active', 'paused', 'archived']).optional(),
  })
  .refine((body) => Object.keys(body).length > 0, { message: 'must change something' });

export const recordCheckInSchema = z.object({
  habitId: habitIdSchema,
  at: z.string().datetime({ offset: true }).optional(),
  reading: z.number().min(0).max(100_000).optional(),
  note: z.string().max(500).optional(),
  source: z.enum(['app', 'watch', 'kiosk', 'import', 'supervisor']).optional(),
  idempotencyKey: z.string().min(8).max(64).optional(),
});

export const amendCheckInSchema = z.object({
  reading: z.number().min(0).max(100_000).nullable(),
});

export const holdSchema = z.object({
  memberId: memberIdSchema,
  range: dateWindow,
  role: z.enum(['onshift', 'offshift', 'travel', 'standby']),
  reason: z.enum(['held_over', 'called_out', 'swapped', 'medical', 'training', 'weather']),
  note: z.string().max(240).optional(),
});

export const shieldSchema = z.object({
  memberId: memberIdSchema,
  range: dateWindow,
  reason: z.enum([
    'annual_leave',
    'sick',
    'compassionate',
    'training',
    'medical_advice',
    'operational',
  ]),
  note: z.string().max(240).optional(),
  grantedBy: memberIdSchema.optional(),
});

export const windowQuerySchema = z.object({
  window: dateWindow,
});

export const policySchema = z.object({
  quietHours: z
    .string()
    .regex(/^\d{2}:\d{2}-\d{2}:\d{2}$/)
    .optional(),
  minGapMinutes: z.number().int().min(1).max(1_440).optional(),
  maxPerDay: z.number().int().min(0).max(12).optional(),
  leadMinutes: z.number().int().min(0).max(4_320).optional(),
  roles: z
    .array(z.enum(['onshift', 'offshift', 'travel', 'standby']))
    .min(1)
    .optional(),
  channel: z.enum(['push', 'email', 'tannoy', 'none']).optional(),
});

export const visibilitySchema = z.object({
  fallback: z.enum(['private', 'lead_only', 'crew']).optional(),
  habitId: habitIdSchema.optional(),
  visibility: z.enum(['private', 'lead_only', 'crew']).optional(),
});

export const createCrewSchema = z.object({
  name: z.string().min(2).max(60),
  site: z.string().min(2).max(40),
  siteZone: z.string().min(3).max(60),
});

export const enrolSchema = z.object({
  memberId: memberIdSchema,
  from: isoDate,
  role: z.enum(['member', 'lead', 'coordinator']).optional(),
});

function detailsOf(error: z.ZodError): DomainErrorDetail[] {
  return error.issues.map((issue) => ({
    path: issue.path.join('.'),
    message: issue.message,
  }));
}

/** Parses a body or query, turning a schema failure into a domain failure. */
export function parseBody<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (result.success) return result.data;
  const details = detailsOf(result.error);
  const first = details[0];
  const summary = first === undefined ? 'is not valid' : `${first.path} ${first.message}`;
  throw new ValidationError(summary.trim(), details);
}
