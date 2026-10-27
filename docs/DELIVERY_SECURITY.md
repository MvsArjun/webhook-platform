# Delivery security and resource bounds — Phase 5

Every outbound request is signed with its endpoint's independent 32-byte secret. The worker preserves the accepted bytes; it never forwards browser sessions, API keys or lease tokens. HTTPS certificate/hostname checks remain enabled. The explicit exact-URL loopback development exception is unchanged.

## Secret setup and storage

All API/worker processes require `RELAY_SIGNING_MASTER_KEY`, Base64 encoding of exactly 32 random bytes. README generates one persistent ignored development file. Keep this value separate from database backups and retain it: changing/losing it prevents decrypting existing endpoint keys. Missing/malformed configuration fails startup without echoing its value. A valid but wrong key fails decryption; no unsigned fallback or silent key replacement occurs. Key rotation/version management is not implemented.

Endpoint keys are provisioned lazily, including for existing endpoints. Concurrent provisioning uses a scoped insert/unique constraint and separate winner read, so all callers receive one stable secret. PostgreSQL stores only a 60-byte AES-256-GCM envelope: random 12-byte IV followed by 32 encrypted bytes and a 16-byte authentication tag. Associated data binds the version domain, workspace UUID and endpoint UUID. Copying ciphertext to another scope or changing it fails authentication. Encryption/signing use Java's JCA implementations, not custom primitives.

An authenticated owner can provision/reveal the current endpoint secret with CSRF protection:

| Method and route | Response |
| --- | --- |
| `POST /api/workspaces/{workspaceId}/endpoints/{endpointId}/signing-secret` | 200 `{secret}`; `Cache-Control: no-store` |

The secret is Base64 encoded and can be revealed again by the owner. This is distinct from platform API keys, which remain hash-only and are revealed only at creation. Foreign/missing endpoints return 404, bearer credentials cannot manage signing secrets, and ordinary endpoint/status/history responses never include them. The payload-loading transaction can provision/decrypt a key, then releases its connection before signing/network work.

Using the authenticated variables from INGESTION.md:

```powershell
$signingSecret = Invoke-RestMethod "$endpointsUrl/$($endpoint.id)/signing-secret" -Method Post -WebSession $relaySession -Headers $headers
```

## Wire signature and verification

The request includes existing event/delivery/attempt UUID headers and positive decimal lifetime `X-Relay-Attempt`, plus:

| Header | Format |
| --- | --- |
| `X-Relay-Timestamp` | Decimal Unix seconds in actual UTC |
| `X-Relay-Signature` | `v1=` followed by 64 lowercase hexadecimal characters |

Decode the shared Base64 secret, then compute HMAC-SHA256 over the UTF-8 prefix below followed immediately by the **exact raw request-body bytes**. There is a newline after the generation and no added newline after the payload:

```text
relay.webhook.v1\n{timestamp}\n{eventId}\n{deliveryId}\n{attemptId}\n{generation}\n{raw payload bytes}
```

Reject missing/repeated/malformed headers, noncanonical UUID text, nonpositive generations and timestamps outside ±300 seconds. Compare the decoded MAC in constant time (`MessageDigest.isEqual` in the sample). Verify before parsing/mutating business state. Re-encoding JSON, changing whitespace or changing any signed identifier invalidates the signature. Retries/replays use a fresh timestamp/attempt signature, while event/delivery IDs and accepted bytes remain stable. Signing uses actual UTC independently of the injected retry-scheduling clock.

A valid signature proves shared-key possession/integrity within the timestamp window; it does not prevent duplicates. Deduplicate by stable event ID, and atomically commit that decision with the business effect. Timestamp checks and signatures do not provide exactly-once delivery.

## Standalone sample receiver

`samples/receiver/Receiver.java` runs directly on the pinned JDK 21, with no database/dependency setup. It independently implements verification and a bounded in-memory deduplication map. It listens only on 127.0.0.1, defaults to port 9090, prints its actual port, and accepts `/webhook`. It never logs bodies, secrets or signatures.

For local testing, first run the API/worker with the same signing master and the development URL exception set to `http://127.0.0.1:9090/webhook` (WORKER.md). Register that exact URL instead of the placeholder HTTPS URL in the ingestion walkthrough. Before submitting events, reveal its secret as above. Store the sample secret without printing it:

```powershell
New-Item -ItemType Directory -Force work | Out-Null
[IO.File]::WriteAllText((Join-Path (Get-Location) 'work/receiver-signing-secret.txt'), $signingSecret.secret)
```

Then in a separate repository-root receiver terminal:

```powershell
$env:RECEIVER_SIGNING_SECRET = (Get-Content work/receiver-signing-secret.txt -Raw).Trim()
$env:PORT = '9090'
& "$env:JAVA_HOME\bin\java.exe" samples/receiver/Receiver.java
```

Submit through the API as in INGESTION.md. A first authenticated event returns 200 `{duplicate:false}`. Repeated authenticated requests with that event ID/body return 200 `{duplicate:true}` without repeating the sample effect; conflicting bytes return 409. The map holds at most 10000 IDs; it returns 503 when full and does not evict remembered IDs. Its state/effect are intentionally in memory and lost on restart; it demonstrates a protocol, not a production durable receiver.

The sample caps requests at 262144 bytes (including chunked bodies), requires UTF-8 JSON media type/identity encoding, limits header bytes/count/values, connections and its executor/queue. JDK request/response timers target 5 seconds with implementation-specific granularity. Oversized bodies return 413; invalid signatures return 401. Stop with Ctrl+C.

## Platform bounds

| Resource | Bound |
| --- | --- |
| Accepted/sent payload | One raw UTF-8 JSON object, 262144 bytes maximum |
| Management API bodies | 16384 bytes maximum; JSON/vendor JSON bounded before parsing, forms bounded by Tomcat; multipart disabled |
| Captured response | `relay.delivery.response-capture-bytes`, default 4096; configurable 0–16384 |
| Response headers | At most 64 fields, 8192 bytes per line |
| HTTP/DNS/queue/time | Bounded pools and whole-request deadline from WORKER.md |
| Workspace active claims | `relay.delivery.workspace-concurrency-limit`, default 4; 1–32 |

Response capture reads at most the configured raw-byte limit plus one byte to detect truncation. It never inflates compressed bodies. UTF-8 errors/control characters are replaced; previews are bounded to 16384 UTF-16 characters and 49152 UTF-8 bytes even in the worst replacement case. Remaining bytes are not drained. A stalled/malformed body after valid response headers retains that known HTTP outcome and marks the preview incomplete; no headers before the deadline remains REQUEST_TIMEOUT. A 2xx response does not promise downstream business completion.

The management filter rejects oversized known-length bodies before authentication/CSRF and reads at most 16385 bytes for JSON, including `application/*+json` and chunked requests. It preserves raw bytes for normal parsing. Forms retain the container's parameter parser and 16 KiB form cap; chunked form failures can surface as a generic authentication/CSRF rejection. Only the event-ingestion POST path keeps the separate 256 KiB streaming validator. Unused multipart parsing is disabled.

History adds `responsePreview` and `responseTruncated`. Previews are untrusted text visible only through the already-scoped history APIs, and may contain receiver-provided sensitive data. They must be rendered as text and are never logged. Setting capture to zero retains no body bytes. SQL constraints and DTO validation enforce bounds. Unknown/synthetic historical outcomes can have null previews.

Workspace limits are persisted at creation; existing workspaces receive four at migration. New-workspace configuration changes do not silently alter existing rows. Workspace API objects expose `deliveryConcurrencyLimit`; changing persisted limits is an operator action in this version. Claimers serialize a workspace's count/increment using a short row lock, count IN_FLIGHT rows across all workers, and skip busy/full workspaces. Completion/recovery free capacity without a separate counter. Expired claims count until recovered. No workspace lock spans HTTP. Limits cap registered claims; requests already sent by an expired worker can still overlap replacement work. Existing claims at upgrade drain normally before lower limits admit more.

The limits are concurrency bounds, not requests-per-second, queue-storage quotas or guaranteed fairness. Customer ingress remains durable and paginated; operational rate/storage policy and measured capacity need deployment-specific work. This project makes no invented throughput or scale claim.

## Destination protections and validation

Production requires HTTPS, rejects internal names and every private/special DNS address, and pins the validated answer set to the actual connection. Mixed public/private answers, numeric private forms, scoped/mapped/transition IPv6 forms and redirect destinations cannot bypass policy. Each scheduled attempt validates again. Redirects, implicit retries, ambient HTTP/SOCKS proxies, cookies and automatic authentication stay disabled. The exact development exception grants one literal-loopback URI only and never disables TLS checks.

Run `mvnw verify`. Tests exercise wire/metadata tampering, timestamp windows, independent receiver verification/concurrent duplicates, encrypted storage/AAD/provisioning races, owner boundaries, log privacy, body/header/framing attacks, self-signed TLS rejection, redirect bypasses, address forms, workspace contention/recovery and all earlier regressions. PROGRESS.md records actual results and the Docker environment gap.

References: [JCA HMAC](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html), [JCA authenticated encryption](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html), [JDK receiver limits](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/module-summary.html), [IANA IPv4 registry](https://www.iana.org/assignments/iana-ipv4-special-registry), [IANA IPv6 registry](https://www.iana.org/assignments/iana-ipv6-special-registry).
