# Changelog

## 1.0.0

The api surface is frozen at this release. The routes in `docs/api.md` and the
error codes beside them are the contract; a test fails if either drifts.

### The shape of it

- Rotation patterns, phases and dated overrides, with a day belonging to the
  site clock on shift and the home clock at home.
- Cadences anchored to the rotation: per day, per role day, per hitch, per
  block and per rolling window, each expanded against the revision of the habit
  that was in force over that stretch.
- Check ins that resolve onto the rotation day they belong to, an append only
  ledger that voids rather than deletes, and a hash chain over it.
- Streaks that step over shielded days and can carry one honest miss a month,
  quotas that say when somebody has slipped too far, and summaries per habit,
  per hitch and per role.
- Nudges planned out of the same obligations the report reads, kept out of rest
  hours, with a dispatch record for what became of each one.
- Sharing that starts closed, so a crew comparison counts only what the viewer
  may read and says how much it left out.

### Running it

- An http api with bearer tokens and scopes, cursor paging, a write limit, a
  request id on every line, prometheus counters and a readiness check.
- A cli covering members, habits, check ins, reports, crews, exports, imports,
  the store and the docs.
- An append only json journal with a writer lock, compaction, backups and a
  retention plan. No database, because there is no database on the boxes this
  runs on.

### Known limits

- One process writes at a time. The lock stops a second one; it does not
  arrange for them to share.
- A rota is one pattern per person. Split rotas, where somebody alternates
  between two, need two members today.
- The digest is written but not delivered. Delivery belongs to whatever the
  operator already uses for mail.

## 0.1.0

The first version anybody other than me ran. Everything above, less the
freezes, the sync endpoint, the templates and the api reference.
