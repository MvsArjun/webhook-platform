# Continuous integration — Phase 7c

`.github/workflows/ci.yml` defines two independent GitHub Actions jobs for pushes, pull requests and manual runs. Both jobs must succeed. There are no deployment steps or production credentials.

**Verification status:** workflow syntax and underlying application checks can be verified locally. A successful GitHub-hosted Linux/Docker run is still required before calling hosted CI verified. This working copy has no Git metadata/remote, Docker is unavailable, and WSL is not installed; no repository was published or workflow dispatched from this environment. See `PROGRESS.md` for the exact checks actually run.

## Jobs and boundaries

| Job | Checks |
| --- | --- |
| Backend and workflow checks | Checksummed actionlint validates the workflow; Docker preflight; the Maven wrapper runs `clean verify`, including unit tests, real PostgreSQL integration tests and packaged-worker/receiver process tests |
| Dashboard build and Chromium journeys | Packages the current backend; fresh `npm ci`; TypeScript/production build; installs the pinned Chromium plus Linux libraries; runs both existing real browser journeys against the API, separate worker and controllable receiver |

Both jobs use a host `ubuntu-24.04` VM, full Temurin JDK **21.0.12.1+1**, and the repository's checksum-verified Maven **3.9.16** wrapper. The dashboard uses `.node-version` (**22.23.4**) and checks bundled npm **10.9.9**. Frontend packages and Playwright **1.64.1** remain fixed by `package-lock.json`; the installed Playwright CLI selects its matching Chromium. No globally installed Playwright is used.

The backend job explicitly unsets `TEST_DB_URL`, `TEST_DB_USERNAME` and `TEST_DB_PASSWORD`, forcing the default Testcontainers path with `postgres:17.11-bookworm`. Docker failure fails the job. Test execution stays serial, with Ryuk/default container cleanup enabled. `mvnw test` alone would omit integration tests and the packaged artifact, so it is not the CI command.

The dashboard job gets a separate disposable `postgres:17.11-bookworm` service. It publishes port 5432 only on loopback, creates `relay_ci_e2e`, and waits for its PostgreSQL health check. The visible `relay-ci-disposable` password is exclusively a credential for this throwaway service. It is not an application/deployment secret and is not needed outside that job. Browser settings are scoped to the final test step:

- `E2E_DB_URL=jdbc:postgresql://127.0.0.1:5432/relay_ci_e2e`
- `E2E_DB_USERNAME=relay_ci` and the matching disposable password
- `E2E_DB_ALLOW_DESTRUCTIVE=true`, only for this initially empty dedicated database

The existing harness validates the database before claiming cleanup ownership, generates temporary signing/control secrets, allocates application ports and starts/stops API, worker, preview and receiver itself. CI does not prestart competing application processes. Linux installation includes Chromium's system libraries via `install --with-deps chromium`; the browser cache path matches the harness. Playwright rejects focused `.only` tests when `CI` is set, preventing an accidental partial browser run. See [browser behavior and setup](DASHBOARD.md#browser-tests).

The dashboard's Maven packaging step deliberately skips tests because its purpose is to supply that job's current JAR. It cannot replace the independent full backend job. No JAR or application state is passed between jobs.

## Workflow access, logs and limits

The workflow requests only `contents: read`, disables persisted checkout credentials, and uses ordinary `pull_request` events. It does not use `pull_request_target`, deployment credentials, repository secret inputs, self-hosted runners or remote publishing. All action references are full commit hashes; the comments identify the reviewed releases:

| Action | Release | Commit |
| --- | --- | --- |
| `actions/checkout` | 7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | 6.1.0 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |
| `actions/setup-node` | 7.1.0 | `949feb2413d6458794dcd2491c4babbbce0c15c1` |

Actionlint **1.7.12** is downloaded into a temporary directory and checked against a fixed SHA256 before execution. Workflow validation covers YAML, Actions expressions and action inputs; optional ShellCheck/Pyflakes checks are explicitly disabled. This does not execute service containers or prove runtime dependency availability. When changing a pin, inspect the official release and resolve its full commit or asset digest, then validate and run both jobs again. A version comment alone does not update a commit pin. The Ubuntu runner image, system-library repositories and versioned PostgreSQL image tag can evolve; this is not an immutable whole-machine build.

Backend test assertions can include synthetic credentials on failure. Maven test output is redirected to a temporary runner file; only anchored numeric test summaries and a fixed failure notice reach the job log, and the original exit code is preserved. Reproduce a failure locally with full output retained privately. Browser output uses the existing safe reporter; traces, videos and automatic failure screenshots remain disabled. There are **no artifact upload steps**: raw reports, child logs, browser captures and `work/` are not published. Dependency caches cover Maven/npm caches, not test databases or reports.

Each job has a 25-minute limit, with narrower backend/browser test limits. New runs cancel older runs for the same workflow/event/pull request or branch. The harness attempts owned-process and fixture cleanup on normal exit and handled signals; hard cancellation can bypass it. Disposable hosted VMs/service containers limit state lifetime. CI is validation only and makes no deployment, delivery reliability or performance claim.

## Reproduce and verify the first hosted run

Use the [README validation commands](../README.md#validation) and [browser-test instructions](DASHBOARD.md#browser-tests) with dedicated databases for local checks. On Docker-capable Linux, the backend command from `backend/` is:

```sh
env -u TEST_DB_URL -u TEST_DB_USERNAME -u TEST_DB_PASSWORD \
  sh ./mvnw -B -ntp clean verify
```

With a separately installed, checksum-verified actionlint 1.7.12, validate from the repository root:

```sh
actionlint -shellcheck= -pyflakes= .github/workflows/ci.yml
```

When this source is placed in the intended GitHub repository, retain `.github/` at the repository root and enable GitHub Actions. A push or pull request runs CI; manual dispatch is available once the workflow is on the default branch. No production secrets need configuring. Inspect the first run for both named jobs, nonzero unit/integration summaries, both browser journeys and a successful overall conclusion. Repository branch protection/required checks are separate repository settings and were not configured here. Do not advertise a passing badge until an actual hosted run succeeds.

Load/failure measurements, final startup/deployment guidance and final project verification remain later Phase 7 work.

Primary references: [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax), [secure workflow use](https://docs.github.com/en/actions/reference/security/secure-use), [service containers](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers), [Playwright CI installation](https://playwright.dev/docs/ci-intro), [actionlint 1.7.12](https://github.com/rhysd/actionlint/releases/tag/v1.7.12). Action pins were checked against the official [checkout release](https://github.com/actions/checkout/releases/tag/v7.0.1), [Java setup release](https://github.com/actions/setup-java/releases/tag/v6.1.0) and [Node setup release](https://github.com/actions/setup-node/releases/tag/v7.1.0) on 2026-10-27.
