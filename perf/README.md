# Load tests (k6)

Measures one application instance against the project targets: **1000 requests/s with p95 < 50 ms**,
plus the HTTP error rate and the CPU and memory of the application, MySQL and Redis.

Everything runs in Docker; nothing but Docker Desktop and the PowerShell that ships with Windows
is needed (Java, Maven, k6 and the MySQL client run in containers).

## Quick start (Windows)

Requirements: Docker Desktop with the WSL 2 engine, running. Give Docker at least 8 CPUs and 8 GB
of memory if the machine has them (`docker info` shows what it has): k6, MySQL, Redis and the
application all share this machine during the test.

Open PowerShell in the repository folder:

```powershell
# 1. A short functional run that checks the set-up (the first one also builds the image).
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\run.ps1 -Smoke

# 2. Capacity steps: one complete run per rate, stopping after the first saturated step.
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\capacity.ps1 -Mix read-heavy -Rates 100,200,300,400 -SkipBuild
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\capacity.ps1 -Mix get-only   -Rates 100,200,300,400 -SkipBuild

# 3. Single runs, e.g. the target load, a Java Flight Recording and its control run without it.
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\run.ps1 -Mix mixed -Rate 1000 -SkipBuild
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\run.ps1 -Mix read-heavy -Rate 200 -Duration 120 -Jfr -SkipBuild
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\run.ps1 -Mix read-heavy -Rate 200 -Duration 120 -SkipBuild

# 4. One comparison table of all runs (also written to perf\results\summary.md).
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\report.ps1
```

`-ExecutionPolicy Bypass` only applies to that one command; it is needed because Windows does not
run unsigned scripts by default. Each run prints PASS or FAIL and writes
`perf\results\<run>\report.md`. **A run deletes the `team-chest` Compose stack and its database
first** and removes it again at the end (`-KeepRunning` keeps it).

Options of `run.ps1`: `-Mix` (a mix of `perf/k6/mixes.json`, default `mixed`), `-Cache on|off`,
`-Rate` (default 1000 req/s), `-Warmup` (60 s), `-Duration` (180 s), `-AppCpus` (2) and
`-AppMemory` (1g) for the application container, `-MinTeams` (1000), `-SkipBuild`,
`-KeepRunning`, `-Jfr` (Java Flight Recording, see below), `-Gc default|parallel` (the JVM's
garbage collector for the test only: `default` leaves the choice to the JVM, which picks Serial
under the 1g limit; `parallel` adds `-XX:+UseParallelGC`; the heap limit stays the same; the run's
directory name ends in `-gc-parallel`), `-Smoke` (50 req/s, 60 s warm-up + 60 s).
`capacity.ps1` passes `-Gc` to every step. `Get-Help .\perf\run.ps1 -Detailed` lists them.

## What a run does

1. **`.env`** (git-ignored): created if missing; variables it lacks (`MYSQL_ROOT_PASSWORD`,
   `MYSQL_PASSWORD`, `JWT_ISSUER`) are added with random passwords and the issuer of
   `.env.example`. Values already set are never changed. For the run, Compose gets these values
   from `.env` only, so the application and token-mint use the same `JWT_ISSUER` and
   `JWT_AUDIENCE`.
2. **Keys and tokens.** `tools/TokenMint.java` (local token issuer, see "Secrets") creates an RSA
   key pair in `.secrets/` once and signs one RS256 token per test user into `perf/.tokens/`.
   Tokens are reused while the seeded users, key pair, issuer and audience are unchanged, there
   are enough join tokens (minted in blocks of 5000) and they are still valid. Before any load,
   the script checks that the running application accepts them.
3. **Fresh stack**: `docker compose down --volumes`, then MySQL 8.4, Redis 7.4 and the application
   image built from the `Dockerfile`, started with `compose.yaml` + `perf/compose.perf.yaml`; the
   script waits until all three are healthy. Flyway creates the schema.
4. **Data** (`perf/sql/seed.sql`): one active campaign and full teams of 30 members, sized from the
   mix and the load (below).
5. **Counters before**: MySQL status and statement statistics (reset), Redis `INFO`, the
   application's timers.
6. **k6** (`perf/k6/load.js`) applies the load while background samplers record `docker stats`
   (every 2-3 s), the application's gauges and timers (every 2 s, two jobs, from the management
   port 8081), the CPU time of the JVM's GC and JIT compiler threads (every 5 s, `/proc` in the
   app container), MySQL's counters and Redis' command counters (every 5 s, `docker compose exec`).
   The JVM logs every garbage collection (`-Xlog:gc`, in `app.log`).
   Just before and after k6 the Docker VM's clocks are compared with this computer's (clock check).
7. **Counters after**, then **invariant checks** (`perf/sql/verify.sql`) on the database; with
   `-Jfr` the recording is copied out. Everything collected is saved in `raw.json`.
8. **Evaluation** (`perf/evaluate.ps1`): `report.md` and `run.json` (with `-Jfr` including the
   recording's summary, `tools/JfrSummary.java`); the stack is removed.

`evaluate.ps1` can be run again on a finished run without repeating the load, e.g. after a fix in
the evaluation; the first report is kept as `report.original.md`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\perf\evaluate.ps1 -ResultDir perf\results\<run>
```

## Scenarios

The workload is **open**: requests arrive at a fixed rate whether or not the server keeps up
(k6 `ramping-arrival-rate`, one scenario per endpoint). A slow server therefore shows up as
latency and *dropped iterations*, not as a quietly lower request rate. The first `-Warmup`
seconds ramp from 0 to `-Rate` (JIT compilation, connection pools, caches) and are not evaluated;
the following `-Duration` seconds at `-Rate` are the measured **steady phase**.

* **Phase of a request**: taken from k6's scenario progress, which uses the monotonic clock of the
  schedule that starts the iterations. Wall-clock time (`Date.now()`) is not used: Docker Desktop
  corrects its VM's clock from time to time, and in one run that moved a wall-clock phase boundary
  by about a second, so steady-phase requests were counted as warm-up.
* **Load generator capacity**: every endpoint's scenario has enough virtual users (VUs) for its
  full rate even if requests take 1 s each (in-flight requests = rate x latency), at least 20,
  all created before the test (about 1000 VUs and 550 MB for k6 at 1000 req/s). k6 runs out of
  VUs only if the server is slower than that; the report lists planned, sent and dropped
  requests per endpoint.
* **Warm-up**: a JVM compiles the hot code only after thousands of requests. The latency-over-time
  table in the report shows whether p95 was already flat when the steady phase began; if it was
  still falling, the warm-up was too short.

The mixes are defined once, in `perf/k6/mixes.json`, which k6 and `run.ps1` both read:

| mix | /get | /progress | /join | /claim | models |
|---|---:|---:|---:|---:|---|
| read-heavy | 90 % | 9 % | 1 % | 0 % | players mostly looking at their team |
| mixed | 70 % | 25 % | 4 % | 1 % | a typical session |
| write-heavy | 30 % | 62 % | 5 % | 3 % | stress for row locks, idempotency inserts and cache invalidation |
| get-only | 100 % | | | | the read path alone; without writes no team is invalidated |
| join-only | | | 100 % | | the join path alone: how many joins per second the join cursor row allows |

Every request is meant to succeed with 200, so any other status is an error:

| endpoint | users and requests |
|---|---|
| `/get` | every seeded user, in a scattered order (each user about once per round over all users) |
| `/progress` | users of teams that are not completed, 1 point per request, a new `Idempotency-Key` each time; the order is scattered so consecutive requests hit different teams, and no team reaches 300 points during a run |
| `/join` | users that are not members yet (`join-000001` ...); they fill new teams one by one |
| `/claim` | members of the completed teams, each exactly once |

`run.ps1` sizes the data from the shares of the chosen mix so that the user pools last for the
whole run (more teams for higher rates or longer runs, at least `-MinTeams`); k6 fails the run
with `pool_exhausted` if a pool still runs out. With `-Mix mixed` and the defaults (1000 req/s,
60 s + 180 s) that is 1000 teams (30 000 users), 77 of them completed, and 9 240 joining users.

## Capacity steps

`perf/capacity.ps1` runs `run.ps1` once per rate (`-Rates`, default 100,200,300,400), lowest
first. Every step is a complete, separate run (fresh stack and data, its own warm-up), so no step
inherits queues, caches or JIT state from the previous one. A step is **saturated** when k6
dropped more than 1 % of the planned steady requests, the throughput stayed below 95 % of the
rate, or p95 was above 500 ms; the remaining, higher rates are then skipped, since they would only
measure longer queues. Meeting the target is judged as in `run.ps1`; a step can miss the target
without being saturated. The table in `perf/results/capacity-<time>-<mix>-cache-<cache>-<cpus>cpu.md`
lists every step with latency, CPU, CPU per request, Tomcat and Hikari gauges and the estimated
cache hit ratio. The capacity lies between the last step that met the target and the next one.

## Measurement quality

Every value in a report comes from a source that either covered the steady phase or is shown as
**"not measured"**; a missing sample is never read as 0.

* **Window**: the steady phase without max(10 s, 5 % of `-Duration`) at both ends.
* **VM clocks**: k6, the JVM and docker stats measure time with the Docker VM's monotonic clock;
  k6 and JFR report times with the VM's wall clock; the samplers use this computer's clock. On the
  test machine the VM's monotonic clock ran about 4.7 % fast, and Docker Desktop keeps the VM's
  wall clock near this computer's by stepping it back (about 0.7 s every 15 s). `run.ps1` reads
  both VM clocks once per second (`clock.csv`); `evaluate.ps1` fits the rate, finds every step and
  converts the steady phase to this computer's clock (and the JFR window to the recording's time
  base). The report states the rate, the steps and how precisely the window is placed. Because of
  the rate, the server's calls per second of this computer are that much higher than k6's req/s;
  the report converts them to VM seconds where they are compared.
* **Window check**: the server's completed requests per second in the two halves of the window
  must agree with each other and with k6's rate (on the same clock) within 5 %; otherwise the
  report and the console say "possibly shifted" (the window may reach into the ramp or past the
  load), and `report.ps1` / `capacity.ps1` show it per run.
* **MySQL statement times**: MySQL's Performance Schema times statements with the wall clock
  (`CLOCK_REALTIME`). A statement that runs while the VM's wall clock is stepped back gets a
  negative duration, which the unsigned arithmetic stores as about 2^64 picoseconds (seen as
  averages of 15 793 445 268 ms). Statement times (`statements.*.time_us`, the COMMIT average) are
  therefore summed only over sampling intervals without a step and are "invalid" if those cover
  less than half the window; digests whose maximum shows a wrapped timer are "invalid" in the
  statements table. Log sync times use the CPU cycle counter and are not affected.
* **Coverage**: for each source the report lists the readings in the window, failed readings and
  the share of the window between the first and the last good reading. Below 80 % the values of
  that source are "not measured". A timer that does not exist yet (an endpoint not called yet)
  counts as 0 calls; a reading without an answer within 5 s is a failure.
* **Management port**: the actuator runs on its own port and Tomcat threads (8081, published on
  this computer only). On the API port its requests queued behind a saturated thread pool, so a
  saturated run had no application samples at all.

## Reading the report

* **Latency: client vs. server**: k6's average time to first byte (`http_req_waiting`) next to the
  server's own mean time (`http.server.requests`). The difference is time outside the server's
  timer: waiting for a Tomcat worker thread, reading the request before the observation starts,
  the network between the containers and k6's scheduling. It is a difference of two averages
  over slightly different requests and clocks, so it is approximate and not the Tomcat queue time
  by itself.
* **Cache hit ratios (approximate)**: estimated from Redis command counts in the window, not counted
  by the application. Every API request runs the token-bucket script (the only `TIME`), every
  write the invalidation script (the only `HGET`), every team-cache miss one fill script; team
  lookups are the `HMGET` calls that are neither. Membership: lookups = `GET`, misses = `SET`/`SETEX`
  (only a member's miss writes the entry). The lookups per second should be close to `/get` calls
  per second; if the counts do not fit this pattern, nothing is estimated.
* **Java Flight Recording** (`-Jfr`, summary in `jfr-summary.md` and at the end of `report.md`):
  * CPU by thread group (API workers, Tomcat poller, Lettuce, JIT, ...) from `jdk.ThreadCPULoad`
    and the JVM total from the container's cgroup (`jdk.ContainerCPUUsage`), plus CPU quota
    throttling.
  * **Exclusive** CPU by component: each execution sample counts once, for the frame closest to
    the top of the stack that is not a JDK class (JWT signature check, JWT parsing, Spring
    Security, Hibernate, JDBC, Redis client, Jackson, Tomcat, application code, ...); these add up
    to 100 %.
  * **Inclusive** CPU by feature: a sample counts for every feature on its stack (security filter
    chain, JWT decode, rate limiter, each service, each cache, ...); features overlap and must not
    be added up.
  * Estimated CPU ms per request (a thread group's CPU spread over its samples; an estimate, only
    the JVM total is measured), hottest methods, allocation rate and sites, GC pauses, where API
    threads wait (connection pool, Redis replies, MySQL replies, monitors; waits over 10 ms) and a
    timeline to check that the window fits the recording.
  * The recording costs CPU itself: compare a `-Jfr` run with the same run without it.

## Rate limiting during the test

Rate limiting stays **on**, so its Redis round trip is part of every measured request.
`perf/compose.perf.yaml` sets explicit per-user limits for the test (e.g. /get 200 burst,
100/s). Because the load is spread over tens of thousands of users, each user sends far less than
one request per second, well below both these and the production limits. **Any 429 fails the
run**: a 429 would mean the test is measuring the limiter instead of the service.

## Redis cache on and off

`-Cache off` sets `team-chest.cache.enabled=false`: every `/get` then reads its team and the
membership from MySQL. Everything else is identical: cache invalidations after writes still run
(other instances could still be caching) and rate limiting still uses Redis. Comparing the two runs
of one mix shows what the cache saves: `/get` latency, MySQL `statements.select` per second and
MySQL CPU. Compare at a rate below saturation, where both runs keep up with the load.

## Pass criteria

All evaluated on the steady phase:

| target | threshold |
|---|---|
| latency | p95 of all requests < 50 ms |
| throughput | at least 99 % of `-Rate` |
| HTTP errors | < 0.1 % (any status other than 200, timeouts included) |
| rate limit | no 429 at all |
| load applied as planned | no dropped iterations, no exhausted user pool |
| correctness | every invariant in `verify.sql` holds |

`verify.sql` checks after every run: no team above 30 members or 300 points, at most one team with
free places per campaign and it is the join cursor's team, team totals equal the sum of their
members, completion flags, no claim before completion, **every member's points equal its seeded
points plus the points of its stored successful `/progress` outcomes** (no lost and no doubled
update), and one stored outcome per claim.

## Results

`perf/results/<date>-<mix>-cache-<on|off>-<rate>rps/` (git-ignored):

| file | content |
|---|---|
| `report.md` | everything below in one page |
| `run.json` | the same as data; `report.ps1` and `capacity.ps1` read it |
| `k6-report.md`, `k6-report.json`, `k6-summary.json`, `k6.log` | k6 results: planned, sent and dropped requests, req/s, p50/p95/p99/max and time to first byte per endpoint, latency over time, non-200 responses by API error code, k6 warnings |
| `docker-stats.csv` | CPU and memory of every container over time |
| `app-gauges.csv`, `app-timers.csv` | application gauges (Tomcat threads and connections of port 8080, Hikari active/idle/pending/max, heap, threads, process CPU) and cumulative timers over time, with failed readings |
| `mysql-status.csv` | MySQL counters (`perf/sql/status.sql`) over time |
| `redis-status.csv` | Redis command counters (`INFO stats commandstats`) over time |
| `clock.csv` | the Docker VM's monotonic and wall clock once per second, with this computer's time |
| `raw.json` | everything else `run.ps1` collected (counters before and after, invariants, settings); `evaluate.ps1` reads it |
| `thread-cpu.csv` | CPU seconds of the JVM's GC threads (`GC Thread#n` and `VM Thread`), JIT compiler threads (C1, C2) and the whole JVM over time, and the number of GC worker threads |
| `app.log` | application log, with the JVM options it picked up, the collector (`Using ...`) and one line per GC pause |
| `app.jfr`, `jfr-summary.md`, `jfr-summary.json` | with `-Jfr`: the Java Flight Recording (open with JDK Mission Control) and its summary |

`report.md` contains: the k6 tables (steady phase, latency over time) and targets; k6 warnings;
the measurement quality (window, clock check, coverage per source); for the window: average and
peak CPU (in cores) and memory per container, application gauges, server-side time per endpoint,
database connection wait and hold time, Redis command time and GC pauses (also over the whole run),
client vs. server latency, MySQL counters per second (statements, rows, row lock waits, deadlocks,
lock wait timeouts, buffer pool reads, fsyncs) and the average time of a COMMIT and of a redo log
and binary log sync, estimated cache hit ratios; the statements with the most server time and
Redis commands, CPU and memory (whole run); a JVM section: the collector (option, JVM log and
metrics), app/JVM/GC/JIT CPU, GC pauses per second, mean and maximum pause, full GCs and the time
spent in pauses, for the warm-up, the window and every 10 s; the invariant checks; the environment,
including the CPUs, heap and garbage collector the JVM sees; with `-Jfr` the recording's summary.

## Finding the bottleneck

Measure first, change one thing at a time, and repeat a run before trusting a difference (two or
three runs per configuration). Where to look:

| symptom | likely limit | look at |
|---|---|---|
| app CPU close to `-AppCpus` cores, CPU quota throttling in the JFR summary | application CPU (JWT signature checks, JSON, JPA, ...) | `-Jfr`: exclusive components and hot methods, CPU ms per request |
| `tomcat.threads.busy` at `tomcat.threads.config.max`, large client vs. server difference | requests waiting for a worker thread (the server is behind) | what the workers do: JFR waits and CPU |
| `hikaricp.connections.pending` > 0, high `db.pool.acquire` | connection pool or slow transactions | `db.pool.usage`, MySQL row lock waits and top statements |
| high `innodb_row_lock_waits` / `innodb_row_lock_time`, deadlocks | lock contention (hot rows: the join cursor, a team) | per-endpoint latency, `/join` and `/progress` p99, `join-only` |
| MySQL CPU high, many `rows/call` or `no index` in top statements | a query plan | the statement in the report |
| low estimated team cache hit ratio | invalidations or entries expiring before the next read | invalidations/s, `get-only` vs. `read-heavy` |
| `/get` slower with cache on than expected, high `redis.command` | Redis round trips | Redis `usec/call`, Redis CPU |
| slow writes, high COMMIT time | making commits durable on the Docker VM's disk (redo log and binary log fsync) | "Durable commits" line: COMMIT, redo log and binary log sync times; write vs. read latency |
| p95 still falling in the latency-over-time table during the steady phase, app CPU high at a low rate | the JVM was still compiling (warm-up too short) | JIT compiler CPU in the JVM section; longer `-Warmup` |
| `jvm.gc.pause` total high, long or full pauses, heap at its maximum | memory / GC | JVM section (pauses, GC threads CPU); `-Gc parallel`; `-AppMemory`, `-Jfr` allocation |
| dropped iterations while the application is far from its limits, k6 using the CPU the other containers need | the load generator itself | k6 CPU in the resources table; a machine with more cores |

## Fair measurement

* k6, MySQL, Redis and the application share one machine and one Docker VM. The numbers describe
  this machine, not production hardware; `report.md` records CPUs, memory, Docker version and
  commit. Close other heavy programs during a run.
* Only the application is limited (`-AppCpus`, `-AppMemory`): "per instance" refers to it. MySQL
  and Redis are not limited, which favours them compared to a shared production database.
* MySQL runs with its defaults (128 MB buffer pool, redo log flushed at every commit), as the
  configuration a reviewer starts with; the report shows whether that is the limit
  (`innodb_buffer_pool_reads`, fsyncs).
* `-Jfr` adds overhead: compare numbers only between runs with the same setting.
* The samplers cost a little CPU themselves (an actuator request every few hundred ms, a
  `docker compose exec` every 2 s for MySQL and every 5 s for Redis and the JVM's threads); they are
  the same in every run since the thread sampler and the GC log were added (runs before them had
  one `docker compose exec` less every 5 s and no GC log).
* A repository inside a OneDrive folder works, but if Docker reports problems with the mounted
  folders (`perf/k6`, `perf/sql`, `perf/.tokens`, `perf/results`, `.secrets`, `tools`), move the
  repository to a plain folder such as `C:\dev`.

## Secrets

Nothing secret is stored in the repository:

* `.env` (database passwords) is created or completed locally with random values and is
  git-ignored; `.env.example` contains only placeholders.
* `tools/TokenMint.java` is a **local token issuer** for development and tests. It stands in for
  the game's authentication service: it holds the private key (`.secrets/jwt-private.pem`,
  git-ignored) and signs tokens. The application only receives the public key, exactly as in
  production; it runs with the `prod` profile and has no token endpoint (`POST /dev/tokens`
  exists only in the `dev` profile). In a real deployment `JWT_PUBLIC_KEY_FILE` and `JWT_ISSUER`
  point to the real issuer.
* `perf/.tokens/` (test tokens, valid for 12 hours) and `perf/results/` are git-ignored.
* During load tests the metrics endpoint (`/actuator/metrics`) is exposed on the management port
  8081, published on `127.0.0.1` only, and only to holders of a valid token (`perf-ops`: the
  management port uses the application's security filter chain); `compose.yaml` alone exposes
  only the health endpoint.

## Without the script

The same steps by hand, for debugging (PowerShell, repository folder), for `-Mix mixed` at the
default load:

```powershell
$compose = 'compose', '-f', 'compose.yaml', '-f', 'perf/compose.perf.yaml'
docker @compose run --rm token-mint perf 30000 10000 /tokens          # keys + tokens
docker @compose down --volumes
docker @compose up --detach --wait --build mysql redis app
docker @compose run --rm -T mysql-tools seed.sql teams=1000 completed_teams=77
docker @compose run --rm -T k6 run -e MIX=mixed -e RATE=1000 -e COMPLETED_TEAMS=77 /scripts/load.js
docker @compose run --rm -T mysql-tools verify.sql teams=1000 completed_teams=77
docker @compose down --volumes
```

`seed.sql` and `verify.sql` need the same `teams` / `completed_teams`; `token-mint perf` needs
`teams x 30` seeded users. One token for manual requests:
`docker compose run --rm token-mint token user-000001`. A recording can be summarised again with
`docker @compose run --rm -T jfr-tools /results/<run>/app.jfr /results/<run>/jfr-summary.md`
(options: `--from-ms`, `--to-ms`, `--cpus`, `--rps`).

## Troubleshooting

| message | cause |
|---|---|
| `docker ... failed` right at the start | Docker Desktop is not running |
| `Unknown -Mix` | the name is not in `perf/k6/mixes.json` |
| `The application rejected a load-test token (HTTP 401)` | the tokens in `perf/.tokens` do not match the application's key pair, issuer or audience: delete `perf/.tokens` and run again |
| `Token check ... returned HTTP 0` | the management port 8081 is not reachable (another program uses it, or the application did not start: `docker compose logs app`) |
| an error about `jwt-public.pem` or the `jwt-public-key` secret | the stack was started before the key pair existed: run `docker compose run --rm token-mint` once |
| k6 exit code 108, "the seed does not match" | tokens and data come from different sizes; run `run.ps1` again (it re-mints when needed) |
| k6 exit code 99 | a target was missed; `report.md` says which |
| `measurement gap: ... covers n % of the window` | a sampler had no good readings for part of the steady phase; its values are "not measured" (see "Measurement quality" in the report) |
| `clock check failed` | the Docker VM's clock could not be read; the window is not corrected for the VM clock |
| many `NO_RESPONSE` errors | the application stopped or restarted (see `app.log`, e.g. out of memory) |
