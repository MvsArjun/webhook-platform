# Destination and worker recovery — Phase 7d2

`npm run demo:failures` runs two bounded scenarios against the packaged API, a separately launched worker, PostgreSQL and an independent verifying HTTP receiver. No failure switches or recovery shortcuts are added to the application. The receiver and client use the pinned Node runtime and existing PostgreSQL dependency.

## Expected behavior

| Scenario | Deliberate fault | Required durable history | Receiver observation |
| --- | --- | --- | --- |
| Destination recovery | First signed request returns 503; the next returns 204 | `FAILED / 503 / HTTP_STATUS` then `SUCCEEDED / 204` | Two verified requests; one simulated effect after recovery |
| Worker recovery | Receiver applies an effect, withholds the response, then the owned worker is forcibly killed | Original remains `IN_PROGRESS` immediately after exit; natural lease expiry records `UNKNOWN`, followed by `SUCCEEDED / 204` | Two verified requests with the same event/payload; one simulated effect and one prevented duplicate |

Each scenario accepts one 1,024-byte event through the real bearer API. Both attempts retain the same event and delivery IDs and exact bytes, with distinct attempt IDs, claim generations 1/2, run ordinals 1/2 and replay generation 0. No manual replay is used. UNKNOWN must retain null HTTP status, failure code, transport duration and response preview; it is an uncertain receiver outcome, not a confirmed destination failure.

The runtime starts one API, then one worker. After destination recovery, it uses that worker for the crash scenario. The controller verifies the receiver's first effect and the committed `IN_FLIGHT`/`IN_PROGRESS` state, arms the expected disconnect, then uses Windows `taskkill /T /F` or Linux `SIGKILL` against only its owned worker. It waits for confirmed process exit and verifies the same unfinished attempt with a still-live lease. A timeout/completion that wins this race invalidates the demonstration. The receiver must observe the disconnect before its held-response gate is released.

A replacement worker uses the same database and temporary signing master. The runner never updates claim timestamps, due times or delivery history to accelerate recovery. It checks that UNKNOWN's database finish time is at or after the original lease deadline. This illustrates the lease recovery path; existing integration tests separately cover competing workers and rejection of stale completion.

Standard request/connect/DNS timeouts are 5s/2s/1s, lease is 30s with 1s safety, and retry policy is five attempts with 1s base/5m cap and equal jitter. The demo explicitly uses one worker slot, the default four-slot workspace limit and 50ms polling. Each scenario has a 90-second observation deadline; requests, SQL, startup and shutdown also have separate finite bounds. In-flight operations can finish after a deadline and cause failure. Each JVM uses G1, a 64–256MiB heap and a five-connection pool. ECS INFO output is discarded after bounded worker readiness; metrics collection is disabled. Loopback HTTP uses the explicit development-only destination exception.

## Run locally

Use pinned Node22.23.4/npm10.9.9, full Temurin21.0.12.1+1 and PostgreSQL17.11. Build the current JAR with the [full backend validation command](../README.md#validation) and install locked frontend dependencies with `npm ci`. Do not run the full backend suite against the demonstration database.

The runner reuses the healthy benchmark's strict `BENCH_DB_*` configuration and advisory lock. Use a **dedicated empty database** ending `_bench` or `_bench_test`, owned by the supplied role, with an explicit loopback host and port. Both tools exclude one another on the same database. Destructive consent is mandatory. Existing customer rows, unexpected tables/schemas or a held lock cause refusal before cleanup ownership. PostgreSQL `fsync`, `full_page_writes` and `synchronous_commit` must be on; the runner checks these and clears inherited PGOPTIONS rather than changing durability settings.

For the standard local PostgreSQL Compose service, create a dedicated database:

```powershell
docker compose exec postgres createdb -U relay relay_failures_bench
```

For native PostgreSQL, create the database using its administration tools. From the repository root, with `JAVA_HOME` set to the pinned JDK:

```powershell
$env:BENCH_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/relay_failures_bench'
$env:BENCH_DB_USERNAME = 'relay'
$env:BENCH_DB_PASSWORD = 'your-dedicated-demonstration-role-password'
$env:BENCH_DB_ALLOW_DESTRUCTIVE = 'true'
Set-Location frontend
npm run test:failures
npm run demo:failures
```

No command-line options are accepted. `BENCH_JAVA_BIN`/`BENCH_JAR` can select an existing runtime/artifact; exact runtime versions and the JAR's SHA256 are recorded. The runner creates temporary customer credentials, API key and signing master, then creates the workspace/endpoint and reveals its signing secret through real session/CSRF-protected APIs. Child environments exclude inherited JVM/Spring overrides.

## Reports and limits

Safe success/failure JSON is written under ignored `work/failure-demo/`. Reports retain only fixed scenario/status labels, runtime/configuration metadata, numeric counts/timings, generation/ordinal history and validation/cleanup booleans. They exclude credentials, customer IDs, destinations, raw payloads/signatures, private paths, response previews and exception text. Failed runs have a fixed failure stage and must not be presented as passing demonstrations.

Receiver verification checks HMAC-SHA256 over the production signing domain, timestamp window, IDs/generation and exact bytes. Headers, body, connections, concurrent handlers and receipt storage are bounded. Completed-response TCP resets are distinct from the explicitly armed held-request crash disconnect; other interrupted requests and parser/connection errors invalidate success. The expected crash request never completes a response. Final counts must be two accepted/durable events, two successful deliveries, four retained attempts, four verified receiver requests, two simulated effects and one prevented duplicate.

The receiver deduplicates its simulated effect in memory by stable event ID plus matching payload digest. It forgets that state on restart. This is an illustration of receiver-side deduplication, not durable business processing or an exactly-once guarantee. A production receiver should commit deduplication with its business effect in durable storage. Requests can still be duplicated, retries are bounded, and eventual delivery is not guaranteed.

Both scenarios are controlled, single-host demonstrations. They do not exercise machine/power loss, database failover, remote networking/TLS, every crash window, sustained fault load or a real destination's business transactions. Observed recovery time includes polling, backoff and process startup and is not an SLO. Retry scheduling uses a JVM clock while leases use the PostgreSQL clock; no exact retry interval is inferred from mixed timestamps.

The runner stops owned children, closes the receiver and removes fixtures only after database ownership is granted and all owned children have stopped. Flyway history remains. Cleanup is attempted on success, failure and handled signals; hard termination can bypass it. Inspect failed-run cleanup before reuse: a later invocation refuses leftover customer data. The harness never force-kills unrelated processes.

## Recorded demonstration

The [retained safe JSON report](results/2026-10-28-failures.json) records a successful run on **2026-10-28, 01:48:30–01:49:19 Asia/Calcutta**. It used Windows10.0.26300 x64, AMD Ryzen5 6600H/12 logical processors, 16,312,721,408 RAM bytes, Node22.23.4, full Temurin21.0.12.1+1 and native PostgreSQL17.11 with all three durability settings on. The settings and packaged JAR hash are retained in JSON.

| Observation | Measured result |
| --- | --- |
| Destination scenario, submission through verified durable success | 1,023.97 ms |
| Intermediate RETRY_WAIT observed | Yes |
| Worker scenario, submission through replacement's verified durable success | 31,147.47 ms |
| Forced worker termination through confirmed exit | 532.64 ms |
| Accepted events / durable deliveries / retained attempts / verified receiver requests | 2 / 2 / 4 / 4 |
| Simulated effects / duplicate effects prevented | 2 / 1 |
| Unexpected receiver request or connection failures | 0 |

Both expected histories and all identity/generation checks passed. The first crash request had no completed response, remained IN_PROGRESS after confirmed process exit with an unexpired lease, and became UNKNOWN at or after that original deadline. One armed crash disconnect and three completed-response resets were counted separately. Both successful deliveries retained their failed/unknown predecessors. All owned processes stopped, the receiver closed and fixtures were removed.

Separate real database probes confirmed refusal and sentinel preservation for a held advisory lock, existing account and unexpected empty schema. Both dedicated databases retained successful V1–V7 with all eight application tables empty and no unexpected schemas. The focused receiver suite and full backend regressions are recorded in `PROGRESS.md`; GitHub CI also runs the real demonstration against a separate disposable database, including the Linux SIGKILL path. Reports and child output are not uploaded.

These are single-run observations, not a recovery-time guarantee. The [healthy load report](LOAD_TESTING.md) remains a separate workload and makes no claim about failure recovery throughput.
