# Relay

A webhook delivery platform built incrementally for backend and infrastructure practice.
**Current scope: Phase 7c.** GitHub Actions is configured for full backend tests, a frontend build and real Chromium journeys; hosted Linux/Docker execution remains unverified. Structured correlation logs, operator-protected metrics, minimal health/readiness probes, graceful worker shutdown and the customer dashboard are available. See [CI jobs and verification limits](docs/CI.md), [observability and operator setup](docs/OBSERVABILITY.md), [shutdown behavior](docs/WORKER_SHUTDOWN.md) and [dashboard use and browser tests](docs/DASHBOARD.md). Load measurements and final deployment verification remain later Phase 7 work.

## Prerequisites and pinned versions

| Component | Version |
| --- | --- |
| Java / Eclipse Temurin | 21.0.12.1+1; Java language target 21 |
| Spring Boot | 4.1.2 |
| Spring Security | 7.1.2, managed by Spring Boot |
| Apache HttpClient / HttpCore | 5.6.5 / 5.4.4, managed by Spring Boot |
| Maven | 3.9.16, downloaded and checksum-verified by the included wrapper 3.3.4 |
| PostgreSQL | 17.11; Compose image `postgres:17.11-bookworm` |
| Node / npm | 22.23.4 / 10.9.9 |
| React / React DOM | 19.3.1 |
| TypeScript / Vite / React plugin | 7.0.2 / 8.3.5 / 6.1.2 |
| Browser test tools | Playwright 1.64.1; its Chromium build; node-postgres 8.23.1 (test harness only) |
| Flyway / Testcontainers / JUnit Jupiter | 12.4.0 / 2.0.5 / 6.0.3, pinned by the Spring Boot dependency BOM |

Install Docker with the Compose v2 plugin for the standard local flow and default integration tests. Java and Node version files are at the repository root. All npm versions are exact and `package-lock.json` fixes the dependency tree. Maven libraries and build plugins use the fixed Spring Boot parent/BOM; there are no floating dependency versions.

Version sources: [Spring support](https://github.com/spring-projects/spring-boot/wiki), [Spring requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Temurin releases](https://adoptium.net/temurin/releases/?version=21), [Node releases](https://nodejs.org/en/about/previous-releases), [PostgreSQL support](https://www.postgresql.org/support/versioning/), [Vite requirements](https://vite.dev/guide/). Exact frontend packages were checked against the npm registry and backend artifacts against Maven Central on 2026-10-23.

## Local startup (PowerShell)

Run from the repository root. Set `JAVA_HOME` to the pinned JDK installation and put its `bin` directory first on `PATH`. Check `java -version` and `node --version` before building. The wrapper uses `JAVA_HOME`; a bare `java` command can otherwise select an older system installation.

Before the first API/worker launch, generate one persistent local signing master key. This writes only an ignored development file and does not print its value:

```powershell
$signingMasterFile = Join-Path (Get-Location) 'work/signing-master-key.txt'
if (-not (Test-Path -LiteralPath $signingMasterFile)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $signingMasterFile) | Out-Null
    $signingBytes = [byte[]]::new(32)
    $signingRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $signingRandom.GetBytes($signingBytes)
        [IO.File]::WriteAllText($signingMasterFile, [Convert]::ToBase64String($signingBytes))
    } finally {
        $signingRandom.Dispose()
        [Array]::Clear($signingBytes, 0, $signingBytes.Length)
    }
}
```

Use this **same key** in every API and worker process and retain it with the database: existing endpoint secrets cannot be decrypted without it. Missing/malformed keys fail startup. Deployments should supply `RELAY_SIGNING_MASTER_KEY` through secret configuration rather than this development file; automatic key rotation is not implemented. Tests inject separate temporary keys and do not use this file.

1. Create local configuration, replace the password placeholder in `.env`, then start PostgreSQL:

   ```powershell
   Copy-Item .env.example .env
   # Edit .env: choose a local-only password before starting PostgreSQL.
   docker compose up -d --wait
   ```

2. In an API terminal, build and start:

   ```powershell
   Set-Location backend
   $env:DB_PASSWORD = 'the-same-local-password-you-put-in-.env'
   $env:RELAY_SIGNING_MASTER_KEY = (Get-Content ../work/signing-master-key.txt -Raw).Trim()
   .\mvnw.cmd verify
   & "$env:JAVA_HOME\bin\java.exe" -jar target/relay-0.0.1-SNAPSHOT.jar
   ```

   Root `.env` configures Compose; Spring does **not** read that file. The command above sets the required backend password. Defaults are `DB_URL=jdbc:postgresql://127.0.0.1:5432/relay` and `DB_USERNAME=relay`. If you change `POSTGRES_PORT`, `POSTGRES_DB` or `POSTGRES_USER` in `.env`, set the corresponding `DB_URL` and `DB_USERNAME` in each backend terminal too. No production secrets are included.

3. In a second terminal, start the same artifact in worker mode:

   ```powershell
   Set-Location backend
   $env:DB_PASSWORD = 'the-same-local-password-you-put-in-.env'
   $env:RELAY_SIGNING_MASTER_KEY = (Get-Content ../work/signing-master-key.txt -Raw).Trim()
   & "$env:JAVA_HOME\bin\java.exe" -jar target/relay-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
   ```

   It logs its database connection, polls PostgreSQL, delivers pending work and opens no HTTP port. Defaults allow four concurrent requests with a five-second deadline and thirty-second lease. API and worker both run Flyway at startup; Flyway serializes migration application. See [worker configuration and destination rules](docs/WORKER.md) before testing a receiver.

4. In a frontend terminal:

   ```powershell
   Set-Location frontend
   npm ci
   npm run build
   npm run dev
   ```

   Open [the local frontend](http://127.0.0.1:5173), create an account, and sign in. Create a workspace to manage its endpoints, API keys and deliveries. Vite proxies `/api` to port 8080; the backend and frontend bind to loopback only. Event submission stays on the bearer REST API rather than storing API keys in the dashboard. See [the customer walkthrough](docs/DASHBOARD.md) and [event ingestion](docs/INGESTION.md).

5. Check the API independently:

   ```powershell
   Invoke-RestMethod http://127.0.0.1:8080/api/system/status
   ```

   Expected JSON: `{"service":"relay","role":"api","phase":7,"database":"connected"}`. The phase number identifies ongoing Phase 7 work; subphases 7a–7b are implemented. This connectivity route queries PostgreSQL on every call and fails if it cannot connect. Flyway must succeed before the API starts. Minimal probes are `GET /actuator/health/liveness` and `GET /actuator/health/readiness`; readiness includes database health, while liveness does not. Metrics are disabled by default and require a separate operator credential; follow [operator setup and metric definitions](docs/OBSERVABILITY.md).

Stop application terminals with Ctrl+C, then `docker compose down`. The named PostgreSQL volume retains data. Changing a password in `.env` after the database has been initialized does not change the stored database password; update the role in PostgreSQL when needed. Never edit an already-applied migration; add `V8__...sql` for the next schema change.

On Linux/macOS, use `sh ./mvnw` instead of `.\mvnw.cmd`, export both database and signing-master environment variables, and use `java -jar ...` to launch. Supply the same persistent Base64-encoded 32-byte signing master to both roles.

## Validation

From `backend`, **`mvnw verify` is the required full backend check** (use the platform's wrapper command). It compiles, packages and runs unit and PostgreSQL integration tests, including signatures, encrypted secrets, independent sample-receiver processes, response/resource attacks, workspace limits and existing ingestion/retry/replay/crash regressions. Scheduling tests advance an injected clock instead of sleeping through backoff. By default Testcontainers starts isolated PostgreSQL 17.11 containers; it does not use the development database. Missing Docker fails verification rather than silently skipping tests. `mvnw test` alone does not execute the `*IT` suite; process tests require the packaged artifact produced by `verify`.

For an existing PostgreSQL installation, create a **dedicated empty test database** and use:

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/relay_test'
$env:TEST_DB_USERNAME = 'your-test-role'
$env:TEST_DB_PASSWORD = 'your-test-password'
.\mvnw.cmd verify
```

The test role must own the database and be allowed to create tables. This path uses the same tests and migrations, with no in-memory substitute. Tests insert and remove their own fixtures; never point them at a shared or production database. Remove the `TEST_DB_*` variables to return to Testcontainers. Frontend validation is `npm ci` followed by `npm run build` in `frontend`. Phase 6 also adds real-browser tests against the packaged API and worker; see [browser-test setup](docs/DASHBOARD.md#browser-tests).

## Repository guide

- `backend/`: Spring Boot API and separately launched worker, migration and PostgreSQL integration checks.
- `frontend/`: React/TypeScript dashboard and Playwright browser tests.
- `compose.yml`, `.env.example`: local PostgreSQL dependency and example configuration.
- [Architecture](docs/ARCHITECTURE.md): modules, current and planned tables, delivery guarantees and transaction boundaries.
- [Phase 1 API and authentication](docs/AUTHENTICATION.md): session/CSRF model, routes and a runnable PowerShell walkthrough.
- [Endpoints and event ingestion](docs/INGESTION.md): Phase 2 routes, payload/idempotency contracts, transaction guarantees and examples.
- [Worker delivery and recovery](docs/WORKER.md): concurrency/deadlines, destination policy, history and transaction boundaries.
- [Graceful worker shutdown](docs/WORKER_SHUTDOWN.md): admission, bounded draining/cancellation and recoverable unfinished work.
- [Observability](docs/OBSERVABILITY.md): structured correlation logs, minimal probes, independent operator access and durable metric definitions/freshness.
- [Continuous integration](docs/CI.md): pinned GitHub Actions, full backend/browser checks, output privacy and first hosted-run verification.
- [Retries and manual replay](docs/RETRIES.md): retryable outcomes, budgets, timing, replay routes and race handling.
- [Delivery security and limits](docs/DELIVERY_SECURITY.md): signing/verification, secret setup, the sample receiver, response capture and workspace capacity.
- [Dashboard](docs/DASHBOARD.md): customer workflows, browser state, local preview and end-to-end verification.
- [Progress](PROGRESS.md): verified state, environment limitations and next phase. Read it before continuing.

Retries stop at the configured budget or a non-retryable result. A receiver may already have processed a timed-out/abandoned request, so retries/replay can duplicate business effects. The sample deduplicates in memory and forgets state on restart; production receivers should commit deduplication with their business effect in durable storage. Workspace limits bound registered in-flight claims rather than undoing requests already sent by an expired worker. Browser sessions live in one API process and end when it restarts. Set `SESSION_COOKIE_SECURE=true` when using HTTPS. No paid infrastructure has been provisioned.

