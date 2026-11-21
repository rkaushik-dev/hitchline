# hitchline

Version 1.0.0. Adherence tracking for people who do not live on a calendar
week. The api surface is frozen at this release; see
[CHANGELOG.md](CHANGELOG.md) for what that covers and what it does not.

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

## Tech stack

- Java 21, built with Gradle 8.14.8 (the wrapper is committed).
- Spring Boot 3.5.16 with Spring Web MVC on embedded Tomcat for the api.
- picocli 4.7.7 for the command line.
- Jackson for JSON. The journal, backups and responses are written in one
  canonical form, so a document written twice is the same bytes.
- JUnit 5, AssertJ and Spring's MockMvc for the tests; JaCoCo holds line
  coverage at 80% or more, and Spotless (palantir-java-format) holds the format.

There is no database. The store is an append only journal of JSON lines with an
in memory index built at open, because there is no database on the boxes this
runs on, and a file that only ever grows is easy to reason about and easy to
back up. `hitchline store compact` drops what a reload would ignore anyway.
The journal format is unchanged, so existing data directories load.

## Building and running

    ./gradlew build              # compile, test, check the format and the coverage gate
    ./gradlew test               # the tests alone
    ./gradlew performanceTest    # the timing budgets, kept out of the default build
    ./gradlew spotlessApply      # format the sources
    ./gradlew installDist        # build/install/hitchline/bin/{hitchline,hitchline-serve}

Then:

    build/install/hitchline/bin/hitchline-serve          # the api on 127.0.0.1:8417
    build/install/hitchline/bin/hitchline --help

    export HITCHLINE_STORE=memory
    build/install/hitchline/bin/hitchline demo seed
    build/install/hitchline/bin/hitchline docs routes

A memory store lives as long as one process, so for anything that should last,
leave `HITCHLINE_STORE` at `file` and the journal goes to
`.hitchline-data/hitchline.jsonl`.

Configuration comes from the environment, or from a JSON file named by
`HITCHLINE_CONFIG` with the environment winning where both say something. The
file may also list the origins a browser may call the api from.

| variable                       | default                           | what it is                                    |
| ------------------------------ | --------------------------------- | --------------------------------------------- |
| `HITCHLINE_STORE`              | `file`                            | `file` or `memory`                            |
| `HITCHLINE_STORE_PATH`         | `.hitchline-data/hitchline.jsonl` | the journal                                   |
| `HITCHLINE_HOST`               | `127.0.0.1`                       | address to bind                               |
| `HITCHLINE_PORT`               | `8417`                            | port to bind                                  |
| `HITCHLINE_LOG_LEVEL`          | `info`                            | `trace` to `error`, or `silent`               |
| `HITCHLINE_WRITES_PER_MINUTE`  | `120`                             | writes one caller may make in a minute        |
| `HITCHLINE_BURST`              | `30`                              | writes one caller may make at once            |
| `HITCHLINE_DEFAULT_ZONE`       | `UTC`                             | the zone used where nobody's own zone applies |
| `HITCHLINE_TOKENS`             | none                              | `token:memberId:read\|write`, comma separated |

## The command line

`hitchline` opens the same store the server does, which is the point: a
coordinator can fix a roster from a terminal while the api is running against
the same journal. A window is written `2025-01-06..2025-01-19` and both ends
are included. A value the domain refuses exits 2, any other failure exits 1.

| command   | what it holds                                                     |
| --------- | ----------------------------------------------------------------- |
| `member`  | `add`, `list`, `roster`, `hold`                                   |
| `habit`   | `add`, `list`, `pause`, `resume`                                  |
| `checkin` | `record`, `list`, `void`                                          |
| `report`  | `adherence`, `open`, `streak`, `nudges`                           |
| `store`   | `status`, `compact`, `backup`, `restore`                          |
| `crew`    | `add`, `enrol`, `members`, `compare`                              |
| `demo`    | `seed`: a crew, their habits and a fortnight of check ins         |
| `export`  | `checkins`, `roster`, `adherence` as CSV, `calendar` as iCalendar |
| `doctor`  | `run`: every check, and what each one found                       |
| `docs`    | `api`, `routes`, `errors`, `openapi`                              |
| `config`  | `show`, `tokens`, `version`                                       |

`hitchline <command> --help` lists each one's options.

## The api

Every response is JSON. Errors carry a `code`, a `message` and a list of the
fields that were wrong, and the code is the part worth branching on. Requests
carry `Authorization: Bearer <token>`; a member may act for themselves and an
admin token for anybody.

| method  | path                                    | scope | what it does                                       |
| ------- | --------------------------------------- | ----- | -------------------------------------------------- |
| `GET`   | `/health`                               | none  | liveness and the clock reading                     |
| `GET`   | `/ready`                                | none  | whether the store and the clock answer             |
| `GET`   | `/metrics`                              | none  | counters in the text exposition format             |
| `GET`   | `/openapi.json`                         | none  | the openapi document                               |
| `POST`  | `/habits`                               | write | give somebody a habit                              |
| `POST`  | `/habits/from-template`                 | write | give somebody a habit from the standard set        |
| `GET`   | `/templates`                            | read  | the standard set                                   |
| `GET`   | `/habits?memberId=`                     | read  | list the habits of one member, paged               |
| `GET`   | `/habits/{habitId}`                     | read  | read one habit                                     |
| `PATCH` | `/habits/{habitId}`                     | write | change a habit                                     |
| `POST`  | `/check-ins`                            | write | record that somebody did the thing                 |
| `GET`   | `/members/{memberId}/check-ins`         | read  | check ins with the rotation day each landed on     |
| `POST`  | `/check-ins/{checkInId}/void`           | write | take a check in out of the count                   |
| `PATCH` | `/check-ins/{checkInId}`                | write | amend a reading                                    |
| `GET`   | `/members/{memberId}/roster`            | read  | the role and the clock for each day                |
| `GET`   | `/members/{memberId}/hitch?on=`         | read  | the hitch a day sits in                            |
| `POST`  | `/roster/holds`                         | write | hold somebody over or send them home               |
| `POST`  | `/roster/shields`                       | write | shield days from scoring                           |
| `GET`   | `/members/{memberId}/adherence`         | read  | how a window went, by habit, by hitch and by role  |
| `GET`   | `/members/{memberId}/habits/{habitId}/streak` | read | the run on one habit                          |
| `GET`   | `/members/{memberId}/open`              | read  | what is still open in a window                     |
| `GET`   | `/members/{memberId}/nudges`            | read  | what the planner would send                        |
| `GET`   | `/members/{memberId}/nudge-policy`      | read  | the nudge policy in force                          |
| `PUT`   | `/members/{memberId}/nudge-policy`      | write | change it                                          |
| `PUT`   | `/members/{memberId}/visibility`        | write | change what somebody shares                        |
| `POST`  | `/members/{memberId}/sync`              | write | take a batch a handset queued while it had no signal |
| `POST`  | `/crews`                                | admin | start a crew                                       |
| `POST`  | `/crews/{crewId}/members`               | admin | put somebody on a crew                             |
| `GET`   | `/crews/{crewId}/members?on=`           | read  | who was on a crew on a day                         |
| `GET`   | `/crews/{crewId}/comparison`            | read  | the crew side by side, minus anything not shared   |
| `GET`   | `/audit`                                | admin | what anybody did, newest first, paged              |

The frozen part of this surface, with every parameter, is the reference in
[docs/api.md](docs/api.md). It is generated from the same manifest the server
describes (`hitchline docs api --out docs/api.md`), and a test fails if the two
drift apart.

## How it is put together

    src/main/java/io/github/rkaushikdev/hitchline/
      domain      pure rules, no io, no framework: time, rotation, crew, habit,
                  schedule, checkin, adherence, nudge
      app         use cases wiring the domain to ports
      config      settings from the environment and the configuration file
      infra       clocks, ids, logging options, shutdown; infra/store holds the
                  journal, the codecs and the stores
      export      CSV and iCalendar
      imports     the CSV reader behind check in and rota imports
      http        the api: the application frame, schemas, views, middleware
                  and routes
      cli         operator commands
      bin         the two entry points, hitchline and hitchline-serve
    src/test/java   unit, integration, invariant and timing tests
