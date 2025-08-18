# hitchline api 0.1.0

Every response is json. Errors carry a code, a message and a list of the fields
that were wrong, and the code is the part worth branching on.

Base url in a default install: http://127.0.0.1:8417

## Routes

### GET /health

liveness and the clock reading

no token needed

### GET /metrics

counters in the text exposition format

no token needed

### POST /habits

give somebody a habit

needs the write scope

### GET /habits

list the habits of one member

needs a token with the read scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| memberId | query | yes | whose habits to list |
| limit | query | no | rows per page, up to 500 |
| after | query | no | cursor from a previous page |

### GET /habits/:habitId

read one habit

needs a token with the read scope

### PATCH /habits/:habitId

change a habit

needs the write scope

### POST /check-ins

record that somebody did the thing

needs the write scope

### GET /members/:memberId/check-ins

list check ins with the rotation day each landed on

needs a token with the read scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| memberId | path | yes | the crew member the record belongs to |
| window | query | no | inclusive day range written as YYYY-MM-DD..YYYY-MM-DD |

### POST /check-ins/:checkInId/void

take a check in out of the count

needs the write scope

### PATCH /check-ins/:checkInId

amend a reading

needs the write scope

### GET /members/:memberId/roster

the role and the clock for each day

needs a token with the read scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| memberId | path | yes | the crew member the record belongs to |
| window | query | yes | inclusive day range written as YYYY-MM-DD..YYYY-MM-DD |

### GET /members/:memberId/adherence

how a window went, by habit and by hitch

needs a token with the read scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| memberId | path | yes | the crew member the record belongs to |
| window | query | yes | inclusive day range written as YYYY-MM-DD..YYYY-MM-DD |

### GET /members/:memberId/nudges

what the planner would send

needs a token with the read scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| memberId | path | yes | the crew member the record belongs to |
| window | query | yes | inclusive day range written as YYYY-MM-DD..YYYY-MM-DD |

### POST /members/:memberId/sync

take a batch a handset queued while it had no signal

needs the write scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| memberId | path | yes | the crew member the record belongs to |

### POST /crews

start a crew

needs the admin scope

### GET /crews/:crewId/comparison

the crew side by side, minus anything not shared

needs a token with the read scope

| parameter | in | required | what it is |
| --- | --- | --- | --- |
| crewId | path | yes | the crew to read |
| window | query | yes | inclusive day range written as YYYY-MM-DD..YYYY-MM-DD |
| on | query | yes | the day to read membership as |

## Errors

| code | status | what it means | what to do |
| --- | --- | --- | --- |
| validation_failed | 400 | A field was missing, malformed, or outside the range the domain allows. | Read the details list, fix the named fields and send it again. |
| not_found | 404 | The id in the path or the body names something this store does not hold. | Check the id. An archived record is gone from every read. |
| conflict | 409 | Two roster entries covering one day, a member in two crews, or a second writer on the journal. | Read what is stored, decide which one is right, and send the one that wins. |
| forbidden | 403 | The token is missing, has the wrong scope, or is acting for somebody other than itself. | Use a token with the scope the endpoint needs, or ask the person to share the habit. |
| unsupported | 422 | A cadence, a backup version or a record shape this build does not understand. | Upgrade, or ask for the shape a supported build writes. |
| internal_error | 500 | An error nobody expected. The detail is in the log against the request id. | Retry once, then send the request id to whoever runs it. |

There are 6 codes in all. Anything not on that list is a bug.
