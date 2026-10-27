# Observability — Phase 7b

The API provides minimal health probes and an operator-protected Prometheus scrape. API and worker application logs use Spring Boot's ECS JSON console format. No monitoring server, new backend service or worker HTTP listener is introduced.

## Access and health

| Route | Access | Meaning |
| --- | --- | --- |
| `GET /actuator/health/liveness` | Public, status only | API process/application liveness; does not query the database |
| `GET /actuator/health/readiness` | Public, status only | API accepts traffic and its database health check succeeds |
| `GET /actuator/prometheus` | Independent operator bearer only; disabled by default | Process meters and cached global delivery metrics |

Healthy probes return `200 {"status":"UP"}`. An unavailable database makes readiness return 503 while liveness can remain UP. Health components, connection details and errors are not returned. Probes use the main API listener, rather than a second port that could appear healthy while the customer listener is unavailable. Readiness does not prove that a worker is running, that a destination is reachable or that delivery will eventually succeed.

Browser sessions and workspace API keys cannot read metrics. An operator token cannot authenticate customer routes and grants no access to other management operations, including environment, logger or shutdown endpoints. Only GET scrape is authorized; other methods and endpoints are denied. JMX endpoint exposure is disabled. Management security follows registered endpoint mappings, including a changed base path, while probe access remains limited to the two exact mapped GET paths.

## Enabling metrics locally

In the repository root, generate a separate operator credential into ignored `work/` without printing it:

```powershell
$operationsTokenFile = Join-Path (Get-Location) 'work/operations-token.txt'
if (-not (Test-Path -LiteralPath $operationsTokenFile)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $operationsTokenFile) | Out-Null
    $operationsBytes = [byte[]]::new(32)
    $operationsRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $operationsRandom.GetBytes($operationsBytes)
        $operationsToken = [Convert]::ToBase64String($operationsBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        [IO.File]::WriteAllText($operationsTokenFile, $operationsToken)
        $operationsToken = $null
    } finally {
        $operationsRandom.Dispose()
        [Array]::Clear($operationsBytes, 0, $operationsBytes.Length)
    }
}
```

Configure the API terminal alongside its existing DB_* and signing-master settings from README, then start the same API artifact:

```powershell
$env:RELAY_OPERATIONS_ENABLED = 'true'
$env:RELAY_OPERATIONS_TOKEN = (Get-Content work/operations-token.txt -Raw).Trim()
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/relay-0.0.1-SNAPSHOT.jar
```

The credential is the canonical unpadded Base64URL encoding of 32 random bytes: exactly 43 characters. Missing/malformed enabled configuration fails startup with a fixed error that does not contain the value or its cause. The token verifier retains only a SHA256 digest and uses constant-time matching. The raw value remains secret configuration; keep the ignored file private or use a deployment secret store. It is independent of API keys and the signing master. The worker needs neither this credential nor a management listener.

With metrics disabled, the collector does not scan delivery tables and scrape requests return 401. A valid operator configuration enables collection and access. Missing, wrong, oversized, malformed or repeated Authorization headers receive an empty 401 with a Bearer challenge. Public probes do not require credentials.

Check the local API without printing the credential:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
$operationsHeaders = @{ Authorization = "Bearer $((Get-Content work/operations-token.txt -Raw).Trim())" }
$scrape = Invoke-WebRequest http://127.0.0.1:8080/actuator/prometheus -Headers $operationsHeaders
$scrape.StatusCode
$scrape.Content
$operationsHeaders = $null
```

This is the local loopback HTTP flow. A deployment must keep operator access private and protect bearer credentials in transit. Changing the configured operator token requires API restart; it does not alter customer credentials.

## Delivery metric definitions

Custom delivery-data meters are **gauges over retained PostgreSQL rows**, not ever-increasing counters. Their values can be reconstructed after worker/API restarts because their source is the ledger; deleting/retaining rows changes their totals. Do not apply Prometheus counter `rate()` semantics to these values. The `relay.metrics.*` freshness/success gauges describe the current API collector and reset on restart. Standard JVM/HTTP/pool meters, also exported by Actuator, are process-local too.

The following are Micrometer names. Prometheus uses underscores; time gauges export seconds, for example `relay.delivery.transport.latency.mean` becomes `relay_delivery_transport_latency_mean_seconds`.

| Meter family | Definition |
| --- | --- |
| `relay.deliveries{status}` | Delivery rows in each of the five current states; queued total is PENDING plus RETRY_WAIT, including paused/future work |
| `relay.attempts{outcome}` | Attempt rows in IN_PROGRESS, SUCCEEDED, FAILED or UNKNOWN; FAILED attempts differ from terminal FAILED deliveries |
| `relay.queue.ready` | Pending or due retry rows with remaining budget and an unpaused endpoint; ignores process/workspace capacity, so it is not a guarantee that a claim can start immediately |
| `relay.queue.ready.oldest.event.age` | Age at refresh of the oldest original `accepted_at` among ready events; zero for an empty ready set |
| `relay.attempts.automatic.retry` | Retained claims with `run_attempt > 1`, including failed/UNKNOWN retries |
| `relay.attempts.manual.replay` | Retained first claims of replay runs (`replay_generation > 0`, `run_attempt = 1`); does not count a replay accepted but not yet claimed |
| `relay.delivery.initial.queue.delay.{samples,mean,max}` | Original `accepted_at` to the lifetime first attempt's `started_at` (`claim_generation = 1`); includes initial pause/capacity/commit time, and does not measure retry or replay queue waiting |
| `relay.delivery.transport.latency.{samples,mean,max}` | Persisted `duration_ms` from finished attempts; covers bounded transport work rather than acceptance-to-success time. UNKNOWN without a duration contributes no sample |
| `relay.metrics.refresh.success` | Last refresh succeeded (1) or failed (0); NaN before any attempt |
| `relay.metrics.snapshot.age` | Monotonic elapsed time since the last successful refresh; NaN before a good snapshot |

There are 21 fixed custom meter IDs. Only the finite state/outcome values are tags; workspace, event, endpoint, attempt, destination, payload and credential values are never custom metric labels. Initial-delay and transport mean/max are NaN when their sample count is zero. Before the first successful observation, delivery data is NaN rather than a misleading zero.

## Collection, freshness and cost

`relay.operations.metrics.refresh-interval` defaults to 5 seconds and accepts 1–60 seconds inclusive. A single daemon task uses fixed delay and never overlaps a refresh. Each refresh uses one aggregate SQL statement in a read-only REPEATABLE_READ transaction, with two-second PostgreSQL/JDBC/transaction timeouts. Pool acquisition still has the existing five-second Hikari bound; this is not a two-second total-refresh guarantee.

The immutable cache is published only after the transaction completes. Scrape meter getters read memory and do not issue delivery SQL. A failed refresh retains the last data and timestamp, reports refresh success 0 and lets age increase. Check freshness alongside all values. A scrape overlapping a cache publication can observe different refreshes between individual meters; it is not a transactional API response.

Ready-event age is the value at the last refresh, not a continuously recomputed queue-wait timer. The collector calculates retained-history aggregates, so its work grows with the table history. The query timeout bounds a slow scan; it does not make large-history scans free. No retention/rollup design, percentile histogram or measured scale claim is introduced here. Closing the API stops collection, bounds its cancellation wait and prevents late publication; native JDBC can still outlive interruption.

## Logs and correlation

Application console records use Boot ECS JSON; the application banner is off and the structured exception object is excluded. JVM/tool output outside the application logger is not guaranteed to use this format. Application code emits fixed messages with a bounded set of fields and does not attach arbitrary exception text.

Invalid signing-master or operator configuration emits a fixed safe setup diagnostic before startup fails. Configured values, placeholder names and exception causes are excluded from these application diagnostics.

Each API request gets a new server-generated UUID in `X-Request-Id`. Caller-provided IDs are ignored. The request's `request_id` is scoped to its thread and restored/cleared after handling. Request-completed records contain a normalized method, known route template (or OTHER), observed status, duration and aborted flag. They do not contain raw paths/queries, headers, customer identities or bodies. An uncommitted uncaught failure is recorded as 500; a response already committed retains its observed status. The logger cannot prove later network delivery of the response.

`acceptance_returned` links `request_id` to the durable event/delivery IDs after the acceptance service has committed; repeated idempotent acceptance produces another return record, not evidence of another insertion. `replay_accepted` records follow a committed replay. Worker records use stable event/delivery IDs plus the unique attempt ID and generation. Join those identifiers to trace acceptance through delivery without forwarding customer credentials.

`completion_committed` is emitted only after fenced completion returns true; `completion_ignored` records rejected late results. `delivery_abandoned` means the current task stopped proceeding: shutdown/lease-budget cases can leave recovery work, while a stale claim may already have been completed or replaced. Delivery-attempt catch records retain the safe claim IDs; worker poll/drain lifecycle records have no claim identifiers. No fencing token, payload, response preview, destination, API/signing/master/operator key or session value is logged. MDC scopes are restored to prevent one request/task contaminating another.

## Validation and remaining work

The backend suite tests actual JSON application logs, hostile client markers, concurrent request IDs, committed acceptance/replay, receiver failure records, scoped worker outcomes, canonical operator tokens, management remapping and role isolation. Real PostgreSQL tests verify metric values, finite cardinality, a locked aggregate query timing out while cached getters remain usable, and decreasing gauges after fixture deletion. Closing an isolated application's pool proves readiness becomes 503 while liveness remains 200.

[CI configuration and successful hosted Linux/Docker results](CI.md) are available. Measured load/failure demonstrations and final deployment/startup verification remain later Phase 7 work. This subphase does not claim worker liveness, guaranteed delivery, tracing across an external customer's business process or measured production capacity.

Primary references: [Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html), [Actuator endpoint access and health groups](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [Prometheus metrics export](https://docs.spring.io/spring-boot/reference/actuator/metrics.html).
