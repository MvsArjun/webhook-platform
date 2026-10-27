# Phase 1 — authentication and workspace access

## Authentication approach

The browser uses a server-side Spring Security session. Login is handled by the standard form-login filter, including session-id rotation and security-context persistence. The cookie is `RELAY_SESSION`, HttpOnly, SameSite=Lax, with a 30-minute idle timeout and cookie-only tracking. Logout invalidates the session, clears authentication and expires the cookie. No remember-me token, HTTP Basic authentication or JWT is enabled.

Sessions are held in the API process. Restart invalidates them, and multiple API replicas would need a shared session store or a documented routing strategy. The current loopback HTTP setup uses `SESSION_COOKIE_SECURE=false`; set it to `true` when serving through HTTPS. Do not expose this local HTTP configuration externally.

Passwords are stored using Spring Security's `DelegatingPasswordEncoder` with BCrypt cost 12 (`{bcrypt}` prefix). Passwords are not trimmed. Registration requires 12–72 Java string characters and at most 72 UTF-8 bytes. Login also rejects candidates over 72 bytes so BCrypt truncation cannot make a longer password authenticate as its 72-byte prefix. Email identifiers are trimmed and lowercased for both registration and login. Registration does not log the user in.

Every browser write, including registration, login and logout, requires CSRF protection. First request `GET /api/auth/csrf`, keeping its session cookie, and send the returned token in its named header. Fetch another token after successful login or logout, because Spring clears the previous token. A missing or invalid token returns 403. The default masked token/session repository handles this flow; JavaScript never needs access to the session cookie. CORS is not enabled; the local frontend uses Vite's same-origin API proxy.

The bearer API uses an independent stateless security chain under `/api/v1/**`. Browser cookies do not authenticate it. Each request must present `Authorization: Bearer <key>`; the key is checked against PostgreSQL on every request. Invalid, malformed, missing and revoked keys return 401 with a Bearer challenge. CSRF is disabled only for this chain because it does not accept cookie authentication.

## API routes

| Method and route | Authentication | Result |
| --- | --- | --- |
| `GET /api/system/status` | Public | 200 live database connectivity |
| `GET /api/auth/csrf` | Public, keeps/creates session | 200 `{headerName,parameterName,token}` |
| `POST /api/auth/register` | CSRF | JSON `{email,password}` → 201 `{id,email}`; invalid input 400, existing normalized email 409 |
| `POST /api/auth/login` | CSRF | Form-encoded `email`, `password` → 204; bad credentials 401 with a generic empty response |
| `GET /api/auth/me` | Browser session | 200 `{id,email}`; unauthenticated 401 |
| `POST /api/auth/logout` | CSRF | 204, session/cookie invalidated |
| `POST /api/workspaces` | Browser session + CSRF | JSON `{name}` → 201 `{id,name,createdAt,deliveryConcurrencyLimit}` |
| `GET /api/workspaces` | Browser session | 200 array of the account's workspaces |
| `GET /api/workspaces/{id}` | Browser session | 200 workspace; missing/other owner 404 |
| `PATCH /api/workspaces/{id}` | Browser session + CSRF | JSON `{name}` → 200 renamed workspace; missing/other owner 404 |
| `POST /api/workspaces/{id}/api-keys` | Browser session + CSRF | JSON `{name}` → 201 metadata plus raw `key`, revealed only in this response |
| `GET /api/workspaces/{id}/api-keys` | Browser session | 200 array of metadata, including revocation timestamps; no raw key/hash |
| `DELETE /api/workspaces/{id}/api-keys/{keyId}` | Browser session + CSRF | 204, including repeat revocation of the same owned key; mismatched workspace/owner or missing key 404 |
| `GET /api/v1/workspace` | Bearer API key only | 200 workspace bound to that key; no caller-selected workspace id |

Names must be nonblank and at most 100 characters and are trimmed before storage. Keys cannot authenticate account or management routes. No response exposes stored password or key hashes. Workspace creation does not select a global "current workspace": each browser operation carries its workspace id and independently enforces ownership in SQL. An account may own multiple workspaces.

## API-key handling

The raw credential has the shape `rly_<public UUID without hyphens>.<secret>`. The secret is 32 cryptographically random bytes encoded as unpadded Base64URL. PostgreSQL stores only the SHA256 digest of the complete raw credential, its public prefix and metadata. Verification uses a constant-time digest comparison. A fast digest is appropriate for this high-entropy random secret; account passwords use adaptive BCrypt instead.

Creation returns `Cache-Control: no-store`. Lists never recover raw credentials or expose hashes. Store a new key securely when created; create a replacement if it is lost. Revocation is checked without a cache on subsequent requests. A request already authenticated before revocation may complete; revocation is not cancellation of in-flight work. Secret-bearing request/response objects redact their string representations, and application code does not log passwords, keys, sessions or CSRF tokens.

## PowerShell walkthrough

Start PostgreSQL and the API using the root README. All example values below are placeholders. Login uses form encoding; registration and management use JSON.

```powershell
$base = 'http://127.0.0.1:8080'
$email = 'you@example.test'
$password = 'replace-with-your-own-password'
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -SessionVariable relaySession
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
$registration = @{ email = $email; password = $password } | ConvertTo-Json
Invoke-RestMethod "$base/api/auth/register" -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body $registration
Invoke-RestMethod "$base/api/auth/login" -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/x-www-form-urlencoded' -Body @{ email = $email; password = $password }

# Login rotates the session and clears the old CSRF token.
$csrf = Invoke-RestMethod "$base/api/auth/csrf" -WebSession $relaySession
$headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod "$base/api/auth/me" -WebSession $relaySession
$workspaceBody = @{ name = 'My portfolio workspace' } | ConvertTo-Json
$workspace = Invoke-RestMethod "$base/api/workspaces" -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body $workspaceBody
$keyBody = @{ name = 'Local REST client' } | ConvertTo-Json
$keyPath = "$base/api/workspaces/$($workspace.id)/api-keys"
$createdKey = Invoke-RestMethod $keyPath -Method Post -WebSession $relaySession -Headers $headers -ContentType 'application/json' -Body $keyBody

# The raw key is available only in $createdKey.key; do not write it to logs.
Invoke-RestMethod "$base/api/v1/workspace" -Headers @{ Authorization = "Bearer $($createdKey.key)" }
Invoke-RestMethod $keyPath -WebSession $relaySession
Invoke-RestMethod "$keyPath/$($createdKey.id)" -Method Delete -WebSession $relaySession -Headers $headers
# Reusing the revoked bearer key now returns 401.
Invoke-RestMethod "$base/api/auth/logout" -Method Post -WebSession $relaySession -Headers $headers
# Reusing the old session can no longer access /api/auth/me.
```

## Validation and remaining scope

`mvnw verify` exercises actual HTTP requests against PostgreSQL. Tests cover registration and validation, normalized duplicate accounts, password hashing/length boundaries, generic failed login, session rotation, logout and replay of old cookies, missing/stale CSRF, two-account workspace/key isolation, key tampering/revocation, hash-only persistence and absence of secret values in captured application output. The original migration and worker-start checks remain in the regression suite.

The [dashboard](DASHBOARD.md) uses these session/CSRF boundaries for registration, login, logout, workspaces and API-key management. [Endpoint management and durable ingestion](INGESTION.md), signed outbound delivery, retries and replay are implemented. Account recovery, email verification and authentication throttling are outside the current scope. The default container path requires Docker; the same integration suite can use a dedicated PostgreSQL database via `TEST_DB_*` as documented in README.

Framework references: [form login](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/form.html), [CSRF integration](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-integration-mobile), [session persistence](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html), [password encoding](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
