# Dashboard — Phase 6

Start PostgreSQL, the API, worker and frontend using [README](../README.md). The browser uses the existing same-origin session APIs; it never needs a bearer key to manage resources. The API remains the authority for workspace ownership and delivery state.

## Customer workflow

1. Create an account and sign in. Registration and login validate credentials, and the browser obtains a fresh CSRF token after authentication changes.
2. Create a workspace, select it, and optionally rename it. Every endpoint, key, event and replay request carries that workspace's ID.
3. Register an HTTPS endpoint. Pause/unpause it from endpoint management. Pausing prevents new acceptance and future claims/replay; an already claimed request can still finish. The exact local-receiver exception requires both backend processes to use the documented development configuration.
4. Reveal the endpoint's signing secret when configuring your receiver. Copy it securely, then dismiss the reveal. See [the signing protocol and sample receiver](DELIVERY_SECURITY.md).
5. Create an API key, copy its one-time value into your REST client and dismiss the reveal. Lists show metadata only; the server cannot recover raw API keys. Revoke a key when it is no longer needed.
6. Submit events through the [bearer REST API](INGESTION.md). The dashboard does not store a bearer credential or alter accepted payload bytes.
7. Inspect events, filter by status and/or endpoint, and open an event's attempt history. A response preview is a bounded, potentially incomplete excerpt from an untrusted destination; it is displayed as text. HTTP success confirms the receiver's response, not completion of downstream business effects.
8. For a terminal failed event on an unpaused endpoint, request replay. The browser sends the observed replay generation; a conflict requires refreshing current state. Replay retains stable event/delivery IDs and history while starting a new bounded attempt run. Duplicate receiver effects remain possible.

## Browser state and limitations

Passwords, raw API keys, signing secrets and CSRF tokens are not saved in local storage or session storage. Raw credentials are held only for the current interaction. Dismiss their reveal before leaving the browser unattended. Workspace/account changes discard the previous view's data; expired sessions return to sign-in. API errors use bounded, fixed user-facing explanations rather than echoing server response bodies or credentials.

Workspace views and event details have hash URLs that can be bookmarked. Filters and page positions stay in component memory and reset when leaving that view. A workspace created in a different browser tab becomes available after a page reload. Narrow screens keep tables within horizontally scrolling panels; event IDs stay on one line.

Events, endpoints and attempts show ten records per page. Filters execute in PostgreSQL before pagination and do not scan just the visible page. The endpoint filter accepts the complete UUID shown on the Endpoints page, so it can target any endpoint. Concurrent inserts can shift offset page boundaries; use the explicit Refresh actions to obtain current state. Loading, empty and error states are explicit. The dashboard is an inspection/management UI, not a live metrics stream. [Operator metrics](OBSERVABILITY.md) and [deployment guidance](DEPLOYMENT.md) were added in Phase 7. Shared sessions, account recovery and authentication throttling remain unimplemented. Public hosting has not been provisioned.

## Browser tests

The browser suite runs Chromium against the built frontend, packaged Spring API, separate worker and PostgreSQL, plus a controllable local receiver. No production test-control endpoint is added. Build the backend with the full `mvnw verify` command from README first; `mvnw test` alone does not package the application. Use the pinned Node runtime and Java 21 (`JAVA_HOME`, or an explicit `E2E_JAVA_BIN`).

Create a **separate, empty database** for browser tests, owned by the test role. With the default Compose role, after starting PostgreSQL:

```powershell
docker compose exec postgres createdb -U relay relay_e2e
```

If you changed `POSTGRES_USER`, substitute that role. For native PostgreSQL, create the same dedicated database using your PostgreSQL administration tools. The runner requires an explicit loopback hostname and port, a database name ending `_e2e` or `_e2e_test`, and explicit cleanup consent. Never use the application database. The test role must own this database and be allowed to create tables and run the fixture cleanup.

From the repository root, with the backend already packaged:

```powershell
Set-Location frontend
npm ci
npm run build
npm run test:e2e:install
$env:E2E_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/relay_e2e'
$env:E2E_DB_USERNAME = 'relay'
$env:E2E_DB_PASSWORD = 'your-dedicated-test-role-password'
$env:E2E_DB_ALLOW_DESTRUCTIVE = 'true'
npm run test:e2e
```

Use the actual PostgreSQL port and role. The test command checks its TypeScript sources before launching processes. The runner rejects existing customer data or unexpected tables before granting itself fixture-cleanup ownership, and isolates Java configuration from inherited Spring/JVM overrides. It allocates API, frontend and receiver ports automatically, supplies the exact development-only receiver exception to API and worker, and uses one disposable signing master for both processes. It does not use or overwrite your persistent development signing key. `E2E_JAR` can select another already-built backend artifact; the default is `backend/target/relay-0.0.1-SNAPSHOT.jar`.

The suite covers the customer journey, authorization boundaries and failed-delivery replay with retained history. It also checks untrusted response text and credential handling. Browser traces, videos, automatic screenshots, server output and arbitrary assertion details are suppressed to keep credentials out of reports. Deliberate preview screenshots are taken only after raw credentials are dismissed, under ignored `work/phase6-preview`. Browser downloads and generated test output also stay under ignored `work/`. The runner stops its child processes and removes test fixtures on exit. PostgreSQL itself remains running for its owner to stop.

This suite uses real PostgreSQL through the explicit external-database path; it does not start Testcontainers. Container startup remains a separate environment-dependent check. The repository's complete backend suite still supports its default isolated Testcontainers flow.
