# Concurrent Team Chest Backend

Backend of the "Team Chest" campaign: players join teams of 30, report the points they earn, and claim
the chest once their team reaches 300 points. Spring Boot 4.1 (Java 21), MySQL 8.4 as the source of
truth, Redis 7.4 for the `/get` cache and rate limiting.

| Document | Contents |
|---|---|
| this file | running the service, request requirements, calling the API by hand, tests |
| [`perf/README.md`](perf/README.md) | load tests (k6) and how to read their reports |

## Requirements

* Docker with Compose v2 (Docker Desktop on Windows/macOS)
* Java 21 only for running the tests outside Docker (`./mvnw` downloads Maven itself)

## Running the service

```bash
cp .env.example .env                      # local passwords and the token issuer
docker compose run --rm token-mint        # creates the RSA key pair in .secrets/ (once)
docker compose up -d --build --wait       # MySQL, Redis and the application on :8080
```

On Windows PowerShell use `Copy-Item .env.example .env` for the first line; the other commands
are the same. `.env` and `.secrets/` are git-ignored.

The application runs with the `prod` profile: it only holds the token issuer's public key
(mounted as a Compose secret) and has no token endpoint. Flyway creates the schema at start-up.

Campaigns are created by game operations, not through this API. For trying it out, create an active
campaign (teams of 30, target 300, reward `{"coins": 500}`):

```bash
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' < tools/demo-campaign.sql
```

```powershell
Get-Content tools/demo-campaign.sql | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
```

Only one campaign may be open at a time, so run it once, or
`docker compose down -v` first to start from an empty database.

Stop everything with `docker compose down` (add `-v` to delete the database).

## Request requirements

### Authentication

Every endpoint needs `Authorization: Bearer <access token>`. The token is a JWT signed with
**RS256** by the trusted issuer, and must carry:

| Claim | Rule |
|---|---|
| `iss` | equals `JWT_ISSUER` (`.env`) |
| `aud` | contains `team-chest-api` (`JWT_AUDIENCE`) |
| `exp` | required, in the future (30 s clock skew allowed) |
| `sub` | the user id, 1–64 characters |

The user is taken only from `sub`; no request body carries a user id, so nobody can act for
another user. Missing or invalid tokens get `401` with `WWW-Authenticate: Bearer`.

In production the game's auth service issues the tokens. Locally `tools/TokenMint.java` plays
that role with the key pair in `.secrets/`:

```bash
docker compose run --rm token-mint token alice      # prints a token for user "alice", valid 12 h
```

### Headers and bodies

| Endpoint | Headers | Body |
|---|---|---|
| `POST /join` | `Authorization` | none |
| `POST /progress` | `Authorization`, `Idempotency-Key`, `Content-Type: application/json` | `{"campaignId": 1, "points": 6}` |
| `POST /claim` | `Authorization`, `Idempotency-Key`, `Content-Type: application/json` | `{"campaignId": 1}` |
| `GET /get` | `Authorization` | none |

* **`Idempotency-Key`**: 1–64 characters of `A-Z a-z 0-9 . _ : -`; a UUID per user action.
  A retry of the same action (lost response, flaky network, second device) must send the
  **same** key and body: it gets the stored answer and nothing is applied twice. The same key
  with a different body gets `422 IDEMPOTENCY_KEY_REUSED`. Keys are scoped per user.
* **`campaignId`** is required on `/progress` and `/claim`; `/join` and `/get` return it.
* **`points`** is a positive integer. A request that would take the team past 300 is clipped.

### Rate limits

Per user (the token's `sub`) and endpoint, token buckets shared by all instances:

| Endpoint | Burst | Refill per second |
|---|---:|---:|
| `/join` | 5 | 1 |
| `/progress` | 20 | 10 |
| `/claim` | 5 | 1 |
| `/get` | 30 | 15 |

Over the limit: `429 RATE_LIMITED` with `Retry-After: <seconds>`; nothing is executed.

### Errors

Every error has the same body:

```json
{"status": 409, "code": "TEAM_COMPLETED", "message": "The team has already reached its target",
 "details": {"campaignId": 1, "teamId": 7, "targetPoints": 300}}
```

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST` | invalid header or body, unreadable JSON |
| 401 | `UNAUTHORIZED` | missing or invalid token |
| 404 | `CAMPAIGN_NOT_FOUND`, `NO_ACTIVE_CAMPAIGN`, `NOT_FOUND` | unknown campaign, no open campaign, unknown path |
| 405 / 415 | `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` | wrong method, body not JSON |
| 409 | `TEAM_COMPLETED`, `CHEST_NOT_READY`, `ALREADY_CLAIMED`, `NOT_A_MEMBER`, `CAMPAIGN_NOT_ACTIVE` | a rule of the campaign rejected the request |
| 422 | `IDEMPOTENCY_KEY_REUSED` | same key, different request |
| 429 | `RATE_LIMITED` | rate limit reached |
| 503 | `SERVICE_BUSY` (+ `Retry-After: 1`) | lock contention outlasted the retries; nothing was committed |
| 500 | `INTERNAL_ERROR` | unexpected failure; no internals are exposed |

The message texts are in `src/main/resources/messages.properties`.

## Calling the API

Example: Alice, Bob and Carol are in one team, Alice and Bob finish
the chest, every one of them claims it once. Start the service and create a campaign first.

```bash
ALICE=$(docker compose run --rm token-mint token alice)
BOB=$(docker compose run --rm token-mint token bob)
CAROL=$(docker compose run --rm token-mint token carol)
API=http://localhost:8080

# join: the first one opens a team, the others join it
curl -s -X POST $API/join -H "Authorization: Bearer $ALICE"
# {"campaignId":1,"teamId":1,"newlyJoined":true}
curl -s -X POST $API/join -H "Authorization: Bearer $BOB"
curl -s -X POST $API/join -H "Authorization: Bearer $CAROL"

# Alice brings the team to 290 points
curl -s -X POST $API/progress -H "Authorization: Bearer $ALICE" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
     -d '{"campaignId": 1, "points": 290}'

# Carol claims too early -> 409 CHEST_NOT_READY
curl -s -X POST $API/claim -H "Authorization: Bearer $CAROL" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{"campaignId": 1}'

# Alice +6 -> 296; Bob +5 is clipped to 4 -> 300, the chest opens
curl -s -X POST $API/progress -H "Authorization: Bearer $ALICE" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{"campaignId": 1, "points": 6}'
curl -s -X POST $API/progress -H "Authorization: Bearer $BOB" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{"campaignId": 1, "points": 5}'
# {"campaignId":1,"teamId":1,"requestedPoints":5,"appliedPoints":4,"userPoints":4,"teamPoints":300,
#  "targetPoints":300,"teamCompleted":true,"version":6}

# Carol +3 after completion -> 409 TEAM_COMPLETED
curl -s -X POST $API/progress -H "Authorization: Bearer $CAROL" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{"campaignId": 1, "points": 3}'

# team view: members by points, ties by who reached the count first
curl -s $API/get -H "Authorization: Bearer $ALICE"

# claims: everyone once; Alice's second claim -> 409 ALREADY_CLAIMED
curl -s -X POST $API/claim -H "Authorization: Bearer $CAROL" \
     -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" -d '{"campaignId": 1}'
# {"campaignId":1,"teamId":1,"reward":{"coins":500},"claimedAt":"...","version":7}

# a retry with the same key replays the first answer and applies nothing
KEY=$(uuidgen)
curl -s -X POST $API/claim -H "Authorization: Bearer $ALICE" \
     -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" -d '{"campaignId": 1}'
curl -s -X POST $API/claim -H "Authorization: Bearer $ALICE" \
     -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" -d '{"campaignId": 1}'
```

Use the `campaignId` that `/join` returned. In PowerShell the same requests look like this:

```powershell
$token = docker compose run --rm token-mint token alice
$headers = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/progress -Headers $headers `
    -ContentType 'application/json' -Body '{"campaignId": 1, "points": 6}'
```

`GET /get` answers:

```json
{
  "campaign": {"campaignId": 1, "phase": "ACTIVE", "startsAt": "...", "endsAt": "...",
            "claimableUntil": "...", "teamCapacity": 30, "targetPoints": 300},
  "team": {"teamId": 1, "memberCount": 3, "teamPoints": 300, "targetPoints": 300, "completed": true,
           "version": 6,
           "members": [{"rank": 1, "userId": "alice", "points": 296, "claimed": false},
                       {"rank": 2, "userId": "bob", "points": 4, "claimed": false},
                       {"rank": 3, "userId": "carol", "points": 0, "claimed": false}]},
  "me": {"rank": 1, "userId": "alice", "points": 296, "claimed": false},
  "canClaim": true
}
```

Before joining, `team` and `me` are `null`, so a client can read the campaign and its `campaignId`
first. `version` grows with every change of the team; a client shows the highest one it has seen.

## Tests

```bash
./mvnw verify          # Windows: mvnw.cmd verify
```

Docker must be running: the integration tests start MySQL 8.4 and Redis 7.4 with Testcontainers
and share one container of each.

* **Unit tests** (`*Test`): domain rules (capacity, clipping at 300, single claim, ranking),
  idempotency hashing and outcome encoding, retry policy, join batcher, error mapping, JWT rules.
* **Integration tests** (`*Tests`): repositories, services and the HTTP API against real MySQL and
  Redis, including concurrent joins and progress on the same team, duplicate idempotency keys in
  flight, the schema constraints, rate limiting, security (wrong issuer, audience, algorithm,
  expired token) and Redis outages (`RedisFailureApiTests` pauses the container).

## Metrics

Prometheus format at `http://<host>:8081/actuator/prometheus`. Locally:

```bash
docker compose exec app curl -s localhost:8081/actuator/prometheus | grep teamchest_
```

Besides Spring's own metrics (`http_server_requests_seconds` per endpoint and status, JVM, GC,
`hikaricp_*`, Tomcat, Redis command times), the service counts:

| Metric | Tags | Meaning |
|---|---|---|
| `teamchest_joins_total` | `result` = `new`, `existing` | answered `/join` requests |
| `teamchest_progress_total` | `outcome` = `applied`, `team_completed`, `not_a_member` | executed `/progress` requests |
| `teamchest_progress_points_total` | | points added to teams |
| `teamchest_progress_clipped_total` | | requests clipped at the target |
| `teamchest_teams_completed_total` | | teams that reached the target |
| `teamchest_claims_total` | `outcome` = `claimed`, `chest_not_ready`, `already_claimed`, `not_a_member` | executed `/claim` requests |
| `teamchest_idempotency_replays_total` | `operation` | retries answered from a stored outcome |
| `teamchest_ratelimit_rejected_total` | `operation` | requests rejected with 429 |
| `teamchest_cache_total` | `cache` = `team`, `membership`; `result` = `hit`, `miss` | `/get` cache lookups |
| `teamchest_join_*_seconds` | | join queue, group transaction, cursor lock wait, hold and commit |

Outcomes are counted once, after the commit; a replayed request is counted only as a replay.

## Load tests

`perf/run.ps1` runs one k6 load test against a fresh stack and checks the invariants in the
database afterwards; `perf/README.md` describes the options. On an AWS EC2 c5d.4xlarge (application
limited to 4 CPU and 1 GB) the mixed load at 1000 req/s had p95 2.96 ms with 0 errors.

## Production notes

* The application speaks plain HTTP; TLS is terminated at the load balancer or reverse proxy in
  front of it.
* Configuration comes from environment variables: datasource, Redis, and
  `TEAM_CHEST_SECURITY_JWT_ISSUER`, `TEAM_CHEST_SECURITY_JWT_AUDIENCE`,
  `TEAM_CHEST_SECURITY_JWT_PUBLIC_KEY_LOCATION` for the issuer's public key. The application does
  not start without an issuer and a key.
* Health and metrics are on the management port 8081, which Compose does not publish:
  `GET /actuator/health` and `GET /actuator/prometheus` need no token there. Redis is not part of
  the health check: the service keeps working from MySQL while Redis is down. On the API port
  8080 the metrics endpoint requires a token like every other path.
* The `dev` profile (`POST /dev/tokens` with `{"userId": "..."}`) exists for local development
  only; the application refuses to start with it together with `prod`.
