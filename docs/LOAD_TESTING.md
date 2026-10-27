# Reproducible load measurements — Phase 7d1

`frontend/bench/run.mjs` runs a bounded healthy workload against the packaged API, a separate worker and PostgreSQL. Its local receiver verifies the actual HMAC signature and exact payload bytes. This subphase measures healthy load; the separate [Phase 7d2 failure demonstrations](FAILURE_DEMONSTRATIONS.md) cover destination and worker recovery.

## Workload and interpretation

Defaults are **100 warmup events**, then **three sequential batches of 1,000 events**, each with **1,024 UTF-8 bytes** and **eight closed-loop clients**. A client sends its next request after the previous request finishes. This is a finite burst at bounded concurrency, not a fixed arrival-rate or maximum-capacity test. Warmup drains completely and contributes no measured samples; batches also drain before the next begins. The database and JVMs stay running across batches, so later batches see warm caches and a larger retained ledger.

One API and one worker run with separate bounded JVM heaps. Worker and workspace concurrency are both four. The poll interval is explicitly **50 ms**, compared with the application's 250 ms default. Request/connect/DNS deadlines and lease settings remain the standard 5s/2s/1s/30s/1s. The receiver requests a 10 ms timer delay before responding 204; scheduling, verification and networking can make actual transport durations longer. Polling, workspace serialization and SQL can limit throughput independently of this delay.

The client, receiver, API, worker and database share one host. Delivery uses the exact development-only loopback HTTP exception. Signing remains enabled, but TLS, remote networking and a production logging/metrics sink are not measured. API/worker ECS logging remains on; output is discarded after the worker's fixed readiness record is recognized, and operator metrics collection is disabled. Results describe this workload and machine, not a production capacity, SLO, exactly-once effect or reliability guarantee. Background processes, power policy and thermal state are not isolated.

## Run locally

Use the pinned Node 22.23.4/npm10.9.9 and full Temurin JDK21.0.12.1+1. First run the [backend validation/build](../README.md#validation) to produce the current packaged JAR, and `npm ci` in `frontend`. The full backend test database must be separate from the benchmark database.

Create a **dedicated empty database** ending `_bench` or `_bench_test`, owned by the supplied role. An explicit loopback host and port are required. For the standard local Compose configuration, create it with:

```powershell
docker compose exec postgres createdb -U relay relay_bench
```

For native PostgreSQL, create the same dedicated database using its administration tools. PostgreSQL must be 17.11 with `fsync`, `full_page_writes` and `synchronous_commit` all on. The harness checks these settings; it does not change them. Ambient `PGOPTIONS` is cleared for the observer connection, and the application children receive an explicit environment that excludes inherited Spring/JVM overrides. Empty V1–V7 tables from an earlier cleaned run are accepted. Existing customer rows, unexpected tables/schemas, missing consent or a held benchmark advisory lock cause refusal before cleanup ownership. Never point this runner at a shared/application database.

From the repository root, with `JAVA_HOME` already set:

```powershell
$env:BENCH_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/relay_bench'
$env:BENCH_DB_USERNAME = 'relay'
$env:BENCH_DB_PASSWORD = 'your-dedicated-benchmark-role-password'
$env:BENCH_DB_ALLOW_DESTRUCTIVE = 'true'
Set-Location frontend
npm run test:bench
npm run bench
```

The runner creates temporary customer credentials and a signing master, registers its endpoint through the real authenticated API and starts the application processes itself. It does not use your persistent development signing key or require prestarted API/worker/receiver processes. `BENCH_JAVA_BIN` and `BENCH_JAR` can select explicit existing runtime/artifact paths; the selected Java version is still checked and the JAR's SHA256 is reported. Each JVM uses G1 and a 64–256 MiB heap; each application database pool is capped at five connections.

Override a bounded workload explicitly, for example:

```powershell
npm run bench -- --events=1000 --iterations=3 --concurrency=8 --payload-bytes=1024 --warmup=100 --receiver-delay-ms=10
```

| Option | Bounds |
| --- | --- |
| `--events` | 100–5,000 per measured batch |
| `--iterations` | 1–5 |
| `--concurrency` | 1–32 |
| `--payload-bytes` | 256–16,384 |
| `--warmup` | 0–500 |
| `--receiver-delay-ms` | 0–1,000 |

Each batch has a 180-second deadline; choosing a large workload/receiver delay can fail to drain within that bound. Requests, SQL, receiver resources and child shutdown also have finite limits. Unknown, duplicate, fractional, ambiguous or out-of-range arguments fail with a fixed diagnostic.

## Report definitions

Safe JSON reports are written under ignored `work/bench/`. They contain machine/runtime/configuration metadata, per-batch counts and distributions, and cleanup results. No credentials, customer IDs, raw URLs, payloads, signatures, private paths or arbitrary exception text belong in a report. A failed run retains a fixed stage/status and must not be presented as a successful measurement. Before retaining or sharing a report, inspect its allowlisted contents.

| Measurement | Definition |
| --- | --- |
| Client acceptance latency | Monotonic time from sending one event through reading/validating its committed 202 response |
| Acceptance throughput | Accepted event count divided by first-send to last-202 elapsed seconds |
| Observed drain throughput | Event count divided by first-send to observing the full cohort committed SUCCEEDED and its verified receiver receipts; includes the observation interval |
| Stored initial queue delay | PostgreSQL `first_attempt.started_at - event.accepted_at` |
| Stored acceptance-to-finish interval | PostgreSQL `attempt.finished_at - event.accepted_at` for the successful first attempt |
| Persisted transport duration | Worker's stored `duration_ms`, distinct from the full acceptance/drain interval |

PostgreSQL's `accepted_at` is assigned from the ingestion transaction's start timestamp. Attempt `finished_at` is sampled before the completion transaction commits. Those deltas include transaction work and are **not exact durable-commit timestamps**. They are not subtracted from client wall-clock values. Client elapsed times use Node's monotonic high-resolution clock.

Each distribution reports count, minimum, arithmetic mean, p50/p95/p99 and maximum in milliseconds. Percentiles linearly interpolate sorted observations at `(n−1) × quantile`; empty samples are null, not zero. Percentiles are reported per batch, never averaged to manufacture an aggregate percentile. All samples must be finite/nonnegative.

Successful batches require an exact match between accepted IDs, durable event/delivery rows, a single successful 204 attempt and a single correctly signed/exact-byte receiver receipt for every event. Retries, unknown outcomes, missing rows, duplicates, invalid signatures or unexpected receiver results invalidate this healthy-load measurement. This is a workload assertion, not an exactly-once guarantee about the platform.

The receiver reports socket errors separately. The pinned HTTP client's immediate connection teardown can send a TCP reset after the receiver has consumed the full request and finished its 204 response. Only that idle, completed-response `ECONNRESET`, with no active request or additional bytes, increments `postResponseResets` without counting a request failure. Resets during a request, parser errors and other connection failures still invalidate the run. `requestFailures` and `connectionFailures` must be zero.

The harness owns only its validated dedicated database and child processes. It attempts to stop children and remove fixtures on success, failure and handled signals. Hard termination can bypass cleanup. Confirm process/database cleanup before reusing a failed run's database; a later invocation refuses leftover customer data.

## Recorded results

The [complete safe JSON report](results/2026-10-28-load.json) records a successful run on **2026-10-28, 01:30:01–01:31:40 Asia/Calcutta** (UTC timestamps are retained in JSON). The machine reported Windows `10.0.26300` x64, AMD Ryzen 5 6600H, 12 logical processors and 16,312,721,408 bytes of RAM (15.19 GiB). Node 22.23.4, full Temurin 21.0.12.1+1 and native PostgreSQL 17.11 were used. PostgreSQL reported 128 MB shared buffers, 100 maximum connections and all three required durability settings on. The packaged JAR's SHA256 is in the report.

These are the default workload/settings described above; warmup is excluded from the table. Each batch accepted and durably completed all 1,000 events with one matching signed 204 attempt and receipt per event.

| Batch | Acceptance events/s | Observed drain events/s | Acceptance RTT p95 (ms) | Stored initial queue p95 (s) | Stored acceptance-to-finish p95 (s) | Transport p95 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 685.3 | 28.2 | 17.5 | 33.087 | 33.108 | 29 |
| 2 | 681.0 | 33.1 | 20.3 | 27.961 | 27.991 | 28 |
| 3 | 868.6 | 63.8 | 16.6 | 13.777 | 13.807 | 25 |

Acceptance took 1.15–1.47 seconds per batch; full observed drain took 15.68–35.48 seconds. Ingestion outpaced delivery and accumulated a queue. The substantial variation across three short batches does not identify a bottleneck or establish sustainable capacity; no profiling or controlled saturation sweep was performed. Read latency and throughput together with the finite burst, warmup, retained ledger, 50 ms worker polling, observer SQL and shared host. TLS, external destinations, a logging sink, enabled metrics, long-duration load, faults and power-loss behavior were not measured.

Including warmup, all **3,100** events, deliveries, successful attempts and verified receiver receipts matched; no duplicate requests, failed/unknown attempts or receiver request failures occurred. All 3,100 completed-response resets were recorded separately. API/worker processes exited, the receiver closed and fixtures were removed. A separate 100-event smoke check passed with deliberately conflicting inherited JVM/Spring/PGOPTIONS settings. Real database guard probes separately confirmed refusal and sentinel preservation for a held advisory lock, existing account and unexpected empty schema. Both dedicated databases retained successful V1–V7 migrations with all eight application tables empty. See `PROGRESS.md` for validation and remaining Phase 7 work.

Primary references: [Node22 high-resolution monotonic timing](https://github.com/nodejs/node/blob/v22.23.4/doc/api/process.md#processhrtimebigint), [PostgreSQL timestamp semantics](https://www.postgresql.org/docs/17/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT), [PostgreSQL WAL/durability settings](https://www.postgresql.org/docs/17/runtime-config-wal.html).
