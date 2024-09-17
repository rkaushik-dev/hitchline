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
- Computes streaks that survive shielded days such as approved leave, so a
  fortnight ashore does not read as a fortnight of failure.

## Layout

    src/domain      pure rules, no io
    src/app         use cases wiring the domain to ports
    src/infra       clocks, id generation, storage adapters
    src/http        the api surface
    src/cli         operator commands
    tests           every test, mirroring the source tree

## Working on it

    npm install
    npm run typecheck
    npm test
