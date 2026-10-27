# Delivery worker — Phase 7b

The same artifact runs separately as API and worker. Start the worker with `--spring.profiles.active=worker`; it has no HTTP listener. It polls PostgreSQL, sends requests outside database transactions and records fenced results. [RETRIES.md](RETRIES.md) defines bounded retry scheduling, budgets and manual replay.

## Claims and execution

`claim(limit, lease)` selects at most 1–100 PENDING or due RETRY_WAIT deliveries with remaining budget and an unpaused endpoint, skipping locked delivery/endpoint rows. An endpoint share lock serializes the decision against pause updates; already committed claims retain their lease after a pause. A claim increments the lifetime generation and per-run counter, generates an opaque token, establishes a database-clock lease and inserts an IN_PROGRESS attempt in one short transaction.

Each worker reserves available execution slots before claiming. It has one polling thread, bounded processing/transport/DNS executors and no unbounded queue of leased work. A separate short transaction loads URL/payload only for the current workspace/event/delivery/token/generation and live lease. That connection is released before DNS or HTTP. A monotonic deadline subtracts load time and safety from the database snapshot's remaining lease.

POST sends exact accepted JSON bytes with `Content-Type: application/json`, stable event/delivery IDs, a unique attempt ID and lifetime claim generation. HMAC signatures/timestamps use the endpoint's encrypted secret; see [DELIVERY_SECURITY.md](DELIVERY_SECURITY.md) for setup and verification. It never forwards API keys, browser credentials or fencing tokens. Any 2xx response headers succeed; other statuses fail with HTTP_STATUS. Redirects and implicit transport retries are disabled. A 2xx response does not claim downstream business processing completed.

The overall deadline covers DNS, connection acquisition, connect/TLS, upload, response headers and bounded preview capture. Separate connect/DNS limits can shorten it. Expiry cancels the request. At most the capture limit plus one raw response byte is read; remaining data is not drained and compression is not inflated. A body failure after observed headers preserves that known status with an incomplete preview. Native DNS may ignore interruption; a fixed bounded daemon pool fails closed when exhausted instead of adding threads.

Completion first locks the full current identity, then checks the live database clock against its lease. Delivery, exact attempt and result metadata finish atomically. Stale, expired or duplicate results change neither state nor history. Inconsistent ledger state rolls back. A database error after HTTP leaves the attempt unfinished for recovery; it is not relabeled as a confirmed receiver failure.

Each poll recovers a bounded batch of expired claims with SKIP LOCKED. Recovery closes the exact old attempt UNKNOWN and schedules RETRY_WAIT, or makes the delivery FAILED when its budget is exhausted, atomically. A next claim gets a new token and higher lifetime generation. Paused work can recover but cannot be claimed until unpaused. Failed HTTP/network attempts remain FAILED in history; retryable outcomes schedule another claim only while the run has budget.

UNKNOWN means the lease expired without an accepted completion. The receiver may have processed the request before the worker died or lost its lease. Recovery can send it again with the same event/delivery IDs. Fencing protects database state; it cannot undo an already sent request. Timeouts are similarly ambiguous. There is no exactly-once or guaranteed eventual-delivery claim. Receiver deduplication is demonstrated in Phase 5.

## Configuration

Supply Spring command-line properties or their standard environment equivalents. Invalid bounds or insufficient lease safety fail startup.

| Property | Default | Bound/purpose |
| --- | --- | --- |
| `relay.worker.concurrency` | 4 | 1–32 processing slots per process |
| `relay.worker.poll-interval` | 250ms | 10ms–10s fixed delay |
| `relay.worker.request-timeout` | 5s | 50ms–120s overall deadline |
| `relay.worker.connect-timeout` | 2s | 1ms–request timeout |
| `relay.worker.dns-timeout` | 1s | 1ms–request timeout |
| `relay.worker.lease` | 30s | 100ms–300s; exceeds request timeout + safety + poll interval |
| `relay.worker.lease-safety` | 1s | 50ms–30s reserved before expiry |
| `relay.worker.recovery-batch` | 100 | 1–100 expired claims per poll |
| `relay.worker.shutdown-grace` | 10s | 100ms–20s for admitted tasks; at most 1s additional cancellation wait |
| `relay.worker.enabled` | true | Disable polling for internal tests |

Worker executor limits are per process. A separate persisted workspace cap limits IN_FLIGHT claims across all workers (default four), serialized in PostgreSQL; full/busy workspaces are skipped and can need another poll. Expired claims count until recovered. No counter is lost on worker termination. Limits cannot undo a request already sent by an expired worker. Native DNS remains bounded but cannot be forcibly terminated inside the JVM. See [graceful draining](WORKER_SHUTDOWN.md) for shutdown behavior. Phase 7b adds structured delivery-attempt logs and API-collected metrics over the shared durable ledger; the worker opens no management listener. See [observability](OBSERVABILITY.md) for correlation fields, access and metric definitions.

## Destination protection

Registration checks HTTPS URI syntax (ASCII host, no userinfo/fragment, valid port), without making a request. Before connecting, the sender rejects internal hostnames, resolves through a validating resolver, and passes exactly those validated IPs to the connection manager. Every DNS answer must be allowed; mixed public/private answers are rejected. IPv4 private/loopback/link-local/multicast/unspecified/special ranges are blocked. IPv6 is conservatively restricted to global unicast outside special/documentation/transition ranges. Some legitimate special-purpose destinations are intentionally rejected.

TLS keeps the original hostname for standard trust-store/hostname verification. Redirects, implicit transport retries, proxies, cookies and automatic authentication are disabled. Address pinning prevents a second unchecked DNS lookup from rebinding a connection to an internal address. Each scheduled attempt validates its destination again. JVM DNS caching may affect availability but does not bypass checks.

For a local receiver only, configure **both API and worker** with the development profile and identical exact literal-loopback URL. Run these in separate repository-root terminals with DB_* and the SAME `RELAY_SIGNING_MASTER_KEY` configured as in README. The signing endpoint can provision/reveal the receiver key before the receiver starts:

```powershell
$receiver = 'http://127.0.0.1:9090/webhook'
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/relay-0.0.1-SNAPSHOT.jar --spring.profiles.active=development --relay.delivery.allow-local-receiver=true "--relay.delivery.local-receiver-url=$receiver"
```

```powershell
$receiver = 'http://127.0.0.1:9090/webhook'
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/relay-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker,development --relay.delivery.allow-local-receiver=true "--relay.delivery.local-receiver-url=$receiver"
```

The exception defaults off. Enabling it outside development, omitting its URL or choosing a nonliteral/nonloopback destination fails startup. Only the configured URI (scheme, host, explicit port, path/query) is permitted. It grants no other local ports/paths, private networks or redirect targets and does not disable TLS verification. The standalone verifying/deduplicating receiver and its setup are in DELIVERY_SECURITY.md.

## Attempt history

| Route | Authentication |
| --- | --- |
| `GET /api/v1/events/{eventId}/attempts` | Workspace bearer key |
| `GET /api/workspaces/{workspaceId}/events/{eventId}/attempts` | Owner session |

Responses are `{items,limit,offset,hasMore}`, default 20/0, limit 1–100, ordered by lifetime generation. Items contain `id`, `deliveryId`, `generation`, `outcome`, timestamps, `httpStatus`, `failureCode`, `durationMillis`, `replayGeneration`, `runAttempt`, `responsePreview`, `responseTruncated`. IN_PROGRESS/UNKNOWN have no confirmed HTTP result. Historical storage-only completions also have null result metadata. Tokens, accepted payloads, digests, keys and URLs are omitted. Preview text is bounded and untrusted. Foreign/missing events return 404.

Fixed failure codes: HTTP_STATUS, REQUEST_TIMEOUT, CONNECT_TIMEOUT, DNS_ERROR, DESTINATION_BLOCKED, NETWORK_ERROR, INTERNAL_ERROR, RESOURCE_LIMIT. Exception messages are never persisted. PostgreSQL enforces tenant references, generation uniqueness and outcome/timestamp/result constraints. History survives recovery and later claims.

After the ingestion walkthrough:

```powershell
Invoke-RestMethod "$base/api/v1/events/$($accepted.eventId)/attempts?limit=20&offset=0" -Headers $bearer
```

## Validation

Run `mvnw verify` as in README. Tests cover real loopback receiver success/failure/timeouts, redirects, bounded concurrency, competing workers, a row lock available during blocked HTTP and actual child-JVM termination/recovery. Test-database lease expiry is forced without long sleeps. Stable event/delivery IDs must survive UNKNOWN then a new successful attempt. Existing PostgreSQL tests cover rollback, stale completion and workspace isolation. Unit tests cover address policy, stale work and deadline budgeting. PROGRESS.md records actual executed results and gaps.

The default Testcontainers path needs Docker; the external PostgreSQL fallback uses the same suite. Tests do not contact production internet receivers.

References: [PostgreSQL locking](https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE), [database clocks](https://www.postgresql.org/docs/17/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT), [Apache DNS contract](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/DnsResolver.html), [Apache configuration](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.html), [IANA IPv4](https://www.iana.org/assignments/iana-ipv4-special-registry), [IANA IPv6](https://www.iana.org/assignments/iana-ipv6-special-registry).
