# Phase 2 — endpoints and durable event ingestion

## Routes

Browser routes use the existing account session; POST/PATCH also require its current CSRF token. Bearer routes use a workspace API key and ignore browser sessions.

| Method and route | Input | Result |
| --- | --- | --- |
| `POST /api/workspaces/{workspaceId}/endpoints` | JSON `{url}` | 201 endpoint |
| `GET /api/workspaces/{workspaceId}/endpoints` | Optional `limit`, `offset` | 200 endpoint page |
| `GET /api/workspaces/{workspaceId}/endpoints/{id}` | — | 200 endpoint |
| `PATCH /api/workspaces/{workspaceId}/endpoints/{id}` | JSON `{paused:true}` or `{paused:false}` | 200 endpoint |
| `POST /api/v1/endpoints/{endpointId}/events` | Raw JSON object; optional `Idempotency-Key` header | 202 immutable acceptance fields |
| `GET /api/v1/events/{eventId}` | — | 200 event status |
| `GET /api/v1/events` | Optional `limit`, `offset`, `status`, `endpointId` | 200 event page for the key's workspace |
| `GET /api/workspaces/{workspaceId}/events/{eventId}` | — | 200 event status for the owner |
| `GET /api/workspaces/{workspaceId}/events` | Optional `limit`, `offset`, `status`, `endpointId` | 200 event page for the owner |

Endpoint objects contain `id`, `url`, `paused`, `createdAt`, `updatedAt`. Event status objects contain `eventId`, `endpointId`, `deliveryId`, `status`, `acceptedAt`, `attemptsInRun`, `maxAttempts`, `replayGeneration`, `nextAttemptAt`; payloads, digests and idempotency keys are omitted. Resource lookups return 404 for missing/foreign resources. During ingestion an existing idempotency conflict takes precedence and returns 409 before endpoint lookup. Browser endpoint writes require ownership in their SQL predicates. API keys cannot manage endpoints, accounts or credentials.

Pages contain `{items,limit,offset,hasMore}`. Defaults are limit 20 and offset 0; limits must be 1–100 and offsets nonnegative. Ordering is descending creation/acceptance time, then UUID for ties. Fetch the next page using `offset + limit` while `hasMore` is true. Offset pages are not a snapshot: concurrent inserts can shift boundaries. Deep offsets are also less efficient than a future cursor design; this phase makes no large-scale pagination claim.

Event lists accept an optional exact uppercase `status` (`PENDING`, `IN_FLIGHT`, `RETRY_WAIT`, `SUCCEEDED` or `FAILED`) and optional UUID `endpointId`. Both filters apply inside the authenticated workspace before pagination and may be combined. Invalid status/UUID values return 400. A foreign or nonexistent endpoint filter returns the same empty page, without disclosing endpoint existence; an owner request for a foreign workspace still returns 404. The dashboard resets its page when a filter changes.

## Endpoint and request validation

Endpoint URLs must be absolute HTTPS URLs with an ASCII host, at most 2048 characters, without credentials or fragments. Explicit ports must be valid. Registration validates syntax only, without DNS or HTTP. Private/local HTTPS URLs may be stored but are blocked by connection-time destination checks. The sole HTTP exception is an exact configured literal-loopback receiver under the default-off development mode described in [WORKER.md](WORKER.md).

Event submission accepts one UTF-8 JSON **object**, without trailing documents, using `Content-Type: application/json` (optional UTF-8 charset). Compressed bodies and repeated/unsupported `Content-Encoding` values are rejected with 415. The maximum body size is **262144 bytes (256 KiB), including whitespace**. Oversized bodies return 413, including unknown-length/chunked requests; the application reads at most the limit plus one byte before rejecting. Invalid JSON, invalid UTF-8, non-object roots and malformed idempotency headers return 400. Parsing happens before the database transaction. The payload is never transformed or logged.

Management requests use a separate 16 KiB cap (including chunked/vendor JSON); login forms use the same container form cap. Multipart is not enabled. These smaller bounds do not reduce the event-ingestion limit.

## Acceptance and idempotency

A 202 response means the event and its pending delivery work have committed in one transaction. Its body is `{eventId,deliveryId,acceptedAt}` and its `Location` points to `/api/v1/events/{eventId}`. Those three fields are immutable, so duplicate acceptance does not depend on a later delivery status. `acceptedAt` is the database transaction timestamp, not a measured commit instant.

`Idempotency-Key` is optional, case-sensitive and workspace-scoped. Supply exactly one header with 1–128 ASCII characters from `A–Z`, `a–z`, `0–9`, `.`, `_`, `:`, `-`.

- Same workspace, key, endpoint UUID and **identical payload bytes**: return the original 202 result without adding work.
- Same workspace/key with another endpoint or different bytes: 409. Whitespace or JSON-property-order changes count as different bytes.
- The same key in another workspace is independent.
- Omit the key to create a new event on every request.

PostgreSQL's unique constraint arbitrates concurrent submissions. The loser of `ON CONFLICT DO NOTHING` uses a separate query at `READ_COMMITTED` to see the winner's committed event and delivery. A delivery insertion failure rolls the transaction back, including the event. A failed/lost HTTP response can still be ambiguous around commit: retry identical input with the same key rather than inventing a new key. Fixed persistence errors do not expose SQL/failing-row details.

Pausing rejects **new** events with 409. An identical retry of a previously accepted request still returns its original result, even after pause. The endpoint share lock serializes acceptance against pause updates. Unpausing permits new acceptance again. Pausing does not erase previously accepted work.

Phase 2 introduced `PENDING` work. The [worker](WORKER.md) independently claims unpaused work, sends bounded HTTP and records history. [Phase 4](RETRIES.md) adds bounded delayed retries and explicit replay from terminal failure. Expired claims retain UNKNOWN history and consume budget. Requests may be duplicated; durable acceptance does not promise eventual or exactly-once delivery.

## PowerShell example

Start PostgreSQL/API as in README and register an account using `AUTHENTICATION.md`. This example signs in with that account and creates fresh resources. All credential values are placeholders.

```powershell
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable relaySession
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
$login = @{ email = 'you@example.test'; password = 'replace-with-your-own-password' }
Invoke-RestMethod "$base/api/auth/login" -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/x-www-form-urlencoded' -Body $login
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $relaySession
$headers[$csrf.headerName] = $csrf.token
$workspace = Invoke-RestMethod "$base/api/workspaces" -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body '{"name":"Webhook demo"}'
$keysUrl = "$base/api/workspaces/$($workspace.id)/api-keys"
$createdKey = Invoke-RestMethod $keysUrl -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body '{"name":"Demo client"}'
$endpointsUrl = "$base/api/workspaces/$($workspace.id)/endpoints"
$endpoint = Invoke-RestMethod $endpointsUrl -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body '{"url":"https://example.com/webhook"}'
$submitUrl = "$base/api/v1/endpoints/$($endpoint.id)/events"
$bearer = @{ Authorization = "Bearer $($createdKey.key)" }
$submitHeaders = $bearer.Clone()
$submitHeaders['Idempotency-Key'] = 'demo-001'
$payload = [Text.Encoding]::UTF8.GetBytes('{"kind":"portfolio.demo","data":{"message":"hello"}}')
$accepted = Invoke-RestMethod $submitUrl -Method Post -Headers $submitHeaders -ContentType 'application/json; charset=utf-8' -Body $payload
$duplicate = Invoke-RestMethod $submitUrl -Method Post -Headers $submitHeaders -ContentType 'application/json; charset=utf-8' -Body $payload
$accepted.eventId -eq $duplicate.eventId
Invoke-RestMethod "$base/api/v1/events/$($accepted.eventId)" -Headers $bearer
Invoke-RestMethod "$base/api/v1/events?limit=20&offset=0" -Headers $bearer
Invoke-RestMethod "$endpointsUrl/$($endpoint.id)" -Method Patch -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body '{"paused":true}'
# The same submitHeaders/payload can still recover the original result after pause.
# A new idempotency key while paused receives 409. No outbound request is sent.
```

## Validation

`mvnw verify` runs the existing authentication/foundation regressions plus real HTTP/PostgreSQL ingestion tests: persistence through a new application/database pool, database-enforced tenant references, injected delivery-insert rollback, concurrent identical/conflicting submissions, workspace-scoped keys, exact-byte conflicts, endpoint validation/CSRF/pause, ownership isolation, pagination and body/header limits (including chunked requests). Sensitive payload markers are checked against captured application output.

The default Testcontainers path needs Docker. This environment uses the documented `TEST_DB_*` fallback against dedicated PostgreSQL 17.11; container startup remains unverified. Relevant PostgreSQL behavior: [read-committed visibility](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED), [conflict insertion](https://www.postgresql.org/docs/17/sql-insert.html#SQL-ON-CONFLICT), [row locks](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).
