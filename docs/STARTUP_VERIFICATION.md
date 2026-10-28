# Startup verification

The automated walkthrough uses the actual repository `compose.yml`, packaged API and worker, Vite **development** server and the source-launched Java sample receiver. It checks a clean start and a retained-data restart. Existing browser journeys separately exercise the built dashboard through Vite preview; the failure controller separately demonstrates503 recovery and a forcibly killed worker.

## Run on a disposable Linux Docker host

Use the pinned full Temurin21.0.12.1+1 and Node22.23.4/npm10.9.9, Docker Engine and Compose2 or newer. `JAVA_HOME` must select that full JDK. The verifier accepts a local Unix-socket Docker endpoint from the default context; remote Docker endpoints and inherited Docker/Compose overrides are rejected or excluded. It does not install prerequisites or provision a host.

From a reviewed checkout root:

```sh
set -eu
(cd backend && env -u TEST_DB_URL -u TEST_DB_USERNAME -u TEST_DB_PASSWORD sh ./mvnw -B -ntp clean verify)
(cd frontend && npm ci && npm run build && npm run test:startup && npm run verify:startup)
```

The backend command runs the full suite using Testcontainers. The startup verifier then creates its own independent disposable database through Compose. No application database, `.env`, BENCH_DB_* or TEST_DB_* settings are required for this walkthrough. `STARTUP_JAVA_BIN` and `STARTUP_JAR` may select existing runtime/artifact paths; versions and artifact hashes are checked/recorded. No command-line arguments are accepted.

The automated verifier is Linux-only because it checks POSIX SIGTERM shutdown. It refuses on Windows before creating files or services. The [interactive PowerShell startup](../README.md#local-startup-powershell) remains the standard manual Windows path when Docker Desktop/Compose and the pinned runtimes are installed. This task's Windows environment has no Docker executable and no installed WSL, so that interactive container path was not executed locally.

## What the walkthrough proves

1. Render the real Compose configuration with a unique generated project, isolated loopback port, database role/password and temporary environment file. Require the expected PostgreSQL17.11 image, service, local binding and project-owned named volume before starting anything. An explicit project flag overrides the file's ordinary `name: relay`; the root `.env` is not used. [Compose project isolation](https://docs.docker.com/compose/how-tos/project-name/)
2. Generate one temporary canonical32-byte Base64 signing master in an ignored restricted file. Start PostgreSQL, apply V1–V7 through the API, start the separate non-web worker and Vite dev server. Check HTML, transformed frontend modules, the same-origin `/api` proxy, status and public liveness/readiness.
3. Register/login, create a workspace, endpoint and API key, reveal the signing secret through the real authenticated/CSRF API, and source-launch `samples/receiver/Receiver.java`. Accept one event and require a single successful200 attempt and the sample's `{"duplicate":false}` response preview. The sample independently verifies HMAC and exact accepted bytes.
4. Confirm that the original browser session is authenticated, then stop API and worker normally. Run Compose down **without** deleting the named volume, and bring it up again. Reuse the same master file and JAR. The old session must be rejected before a new login; the original account, workspace, event, encrypted endpoint key and API key must remain usable. Decrypting the original endpoint secret must return the same key. A second event must succeed with one200 attempt.
5. Require successful V1–V7 and exactly two durable events, deliveries and attempts. Stop owned children; normal application shutdown must finish within the verifier's grace. Remove only the unique generated Compose project's containers, network and volume, verify absence, and delete only the temporary secret/environment files the run created.

This is an automated equivalent of the documented local setup, with isolated ports, throwaway credentials and a temporary master retained across the restart. It does not use or overwrite a developer's persistent signing file or ordinary `relay` Compose volume. The ordinary `docker compose down` command in README preserves development data; the verifier's final volume deletion is intentionally restricted to its newly owned disposable project. [Compose named volumes and ownership labels](https://docs.docker.com/reference/compose-file/volumes)

## Reports, failure and cleanup

The command prints a fixed pass/failure notice. After successful preflight, safe JSON is written under ignored `work/startup/`; preflight refusal creates no owned resources or report file. Reports contain fixed result/cleanup booleans, aggregate counts, runtime versions and artifact/configuration hashes. Credentials, IDs, destinations, raw payloads/signatures, private paths, Compose rendering, container logs and exception text are suppressed.

The walkthrough has an eight-minute work deadline and a separate bounded cleanup period. HTTP, SQL, source-receiver startup and Docker commands have narrower limits. It tracks owned processes and Docker CLI process groups; a forced application-stop fallback invalidates success. Cleanup is attempted on failure and handled signals. Hard cancellation or host loss can bypass it. On a shared host, inspect a failed run's uniquely scoped resources before reusing or removing them; never prune unrelated containers/volumes or delete the ordinary development volume.

The SIGTERM checks occur after confirmed deliveries, without a deliberately held active request. Controlled lifecycle/unit tests separately cover draining and cancellation during active work; this walkthrough does not add a loaded OS-signal or containerized-worker shutdown claim.

## Final verification boundaries

| Area | Evidence and boundary |
| --- | --- |
| Backend | Full JUnit/PostgreSQL suite, including auth/isolation, transactional ingestion, retries/replay, signing/SSRF, fencing, shutdown and metrics |
| Frontend | TypeScript/production build plus two real Chromium customer journeys; startup verifier separately checks Vite dev compilation/proxy |
| Failure behavior | Actual503→204 and forced-worker UNKNOWN→SUCCEEDED demonstrations, preserving attempt history |
| Load | Three finite1,000-event batches on the recorded machine; [observed results and limits](LOAD_TESTING.md), without a production-capacity claim |
| Setup and persistence | This Compose walkthrough, retained volume/master and successful delivery before/after restart |
| Public HTTPS deployment | [nginx/systemd reference](DEPLOYMENT.md), reviewed instructions; services, TLS origin, backup restore and public deployment were not provisioned or executed |

**Verified on hosted Linux:** [run40661856462](https://github.com/MvsArjun/webhook-platform/actions/runs/40661856462), executable/configuration revision `4f61fc222c197a56b78bb7a4ae9d53dee80193c1`, completed successfully on **2026-10-28 at14:41:50 Asia/Calcutta**. Both jobs passed:159 backend tests,28 Node checks, both Chromium journeys, both real recovery scenarios and this startup walkthrough. Fresh locked install reported zero audit vulnerabilities and the production frontend build passed. The startup success notice is emitted only after the retained-data assertions and all cleanup gates succeed. No report/log artifacts were uploaded.

Final documentation records that verified executable revision; the final `main` run validates the complete published source again. GitHub CI runs this walkthrough after the existing browser and failure checks, against its own Compose project rather than the earlier service database. Passing startup tests do not establish production availability, power-loss recovery, durable receiver deduplication or delivery guarantees.
