# hitchline

Adherence tracking for people who do not live on a calendar week.

A crew member on a 14 and 14 rotation does not have Mondays. They have day one
of a hitch, a travel day at each end, and a fortnight at home where the same
routine means something different. Every habit tracker I tried counts calendar
days and breaks a streak the moment somebody boards a helicopter, so this one
counts rotation days instead.

## What it does

- Holds the rotation pattern for each crew member and works out, for any date,
  whether that person is on shift, off shift, travelling or on standby.
- Lets a habit be scheduled against those roles. "Three times a hitch" and
  "every off shift morning" are cadences the engine understands directly.
- Records check ins against the site timezone in force at that moment, which is
  not always the timezone the phone was in.
- Computes streaks that survive shielded days such as approved leave, and lets
  a run carry one honest miss a month rather than collapsing.
- Says when somebody has slipped too far, which is a different question from
  how good their last run was.
- Keeps everything private until the person shares it. A crew comparison counts
  only what the viewer is allowed to read, and says how much it left out.

## The idea in one example

    hitchline member add --name "Ada Vasquez" \
      --pattern 14/14 --anchor 2025-01-06 \
      --home Europe/Lisbon --site Europe/Oslo

    hitchline habit add mem_... --name "Twenty minutes on the treadmill" \
      --cadence 1/onshift-day --target "at least 20 minutes"

    hitchline report adherence mem_... --window 2025-01-06..2025-01-19 --shape

The habit asks for nothing at all during the fortnight ashore, the check in Ada
records at 23:40 on the last night offshore lands on the shift day it belongs
to, and the streak survives the flight home.

## Cadences

| written                | means                                                          |
| ---------------------- | -------------------------------------------------------------- |
| `1/day`                | once every calendar day                                        |
| `2/onshift-day`        | twice on each day the rota says on shift                       |
| `1/onshift+travel-day` | once on shift days and travel days                             |
| `3/hitch`              | three times per turn of the rotation, any days                 |
| `1/onshift-block`      | once per unbroken run offshore, however long                   |
| `5/14d`                | five times per fortnight, tiled from the day the habit started |

## Running it

    npm install
    npm run dev          # the api on 127.0.0.1:8417
    npm run cli -- --help

    HITCHLINE_STORE=memory npm run cli -- demo seed
    HITCHLINE_STORE=memory npm run cli -- report adherence mem_0000000000000001 \
      --window 2025-01-06..2025-01-19

Configuration comes from the environment, or from a json file named by
`HITCHLINE_CONFIG` with the environment winning where both say something.

| variable               | default                           | what it is                      |
| ---------------------- | --------------------------------- | ------------------------------- | ----------------------- |
| `HITCHLINE_STORE`      | `file`                            | `file` or `memory`              |
| `HITCHLINE_STORE_PATH` | `.hitchline-data/hitchline.jsonl` | the journal                     |
| `HITCHLINE_HOST`       | `127.0.0.1`                       | address to bind                 |
| `HITCHLINE_PORT`       | `8417`                            | port to bind                    |
| `HITCHLINE_LOG_LEVEL`  | `info`                            | `trace` to `error`, or `silent` |
| `HITCHLINE_TOKENS`     | none                              | `token:memberId:read            | write`, comma separated |

## How it is put together

    src/domain      pure rules, no io, no framework
    src/app         use cases wiring the domain to ports
    src/infra       clocks, ids, the journal and the stores
    src/http        the api surface
    src/cli         operator commands
    tests           unit, integration, invariants and timings

The store is an append only journal of json lines with an in memory index built
at open. There is no database because there is no database on the boxes this
runs on, and a file that only ever grows is easy to reason about and easy to
back up. `hitchline store compact` drops what a reload would ignore anyway.

The api reference in [docs/api.md](docs/api.md) is generated from the same
manifest the server registers, and a test fails if the two drift apart.

## Working on it

    npm run typecheck
    npm run lint
    npm test              # unit, integration and invariants
    npm run test:perf     # the timings, kept out of the default run
    npm run test:coverage
    npm run docs          # regenerate docs/api.md
