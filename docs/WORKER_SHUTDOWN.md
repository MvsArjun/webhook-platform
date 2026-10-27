# Graceful worker shutdown — Phase 7a

Normal Spring application shutdown first closes the worker's admission barrier. It cancels future polling without interrupting a claim transaction already in progress and stops executor submissions. Already admitted delivery tasks can finish HTTP and persist their fenced results while the database and sender remain available. No database or network operation runs under the admission monitor.

A claim transaction that was already running can commit after admission closes. Claims that were never submitted to the delivery executor remain `IN_FLIGHT` with an `IN_PROGRESS` attempt; they are recovered through the existing lease mechanism. Shutdown does not reset budgets, delete attempts, release another worker's claim or bypass token/generation/lease fencing.

## Configuration and timing

| Property | Default | Bounds |
| --- | --- | --- |
| `relay.worker.shutdown-grace` / `RELAY_WORKER_SHUTDOWN_GRACE` | `10s` | `100ms` through `20s`, inclusive |

The grace period is independent of the request timeout and lease. Keep it long enough for the request budgets you want to drain. A shorter grace is permitted: unfinished work will need lease recovery and may be delivered again. The same worker instance cannot restart after stopping; launch a new worker process instead.

One monotonic deadline covers the whole drain. When it expires, continuation gates close and the processing/polling executors are interrupted. The worker then waits at most one additional second in total for cancellation cleanup. A late task cannot start another payload load, send after its next gate, or begin a completion write after observing a closed gate. A completion transaction already entered before cutoff may still commit a legitimate result under the normal database fences.

The asynchronous `SmartLifecycle.stop(callback)` path and synchronous `stop()` share the same drain. Each registered callback runs after the drain finishes or reaches its bounded cancellation wait. A destruction callback also joins that drain, including a stop that was already initiated before context closure or an aborted startup. This keeps dependency destruction from prematurely closing the sender/data source during normal shutdown.

Boot 4.1.2 defaults `spring.lifecycle.timeout-per-shutdown-phase` to 30 seconds, longer than the maximum 20-second grace plus one-second cancellation wait. If changing that framework timeout, keep it above the worker bound and allow additional bean-destruction time. A host, service manager or container must allow that time before forcing termination. Compose currently runs only PostgreSQL; no worker container or deployment service is introduced in this subphase.

Start a worker in a repository-root terminal with DB_* and the persistent signing master configured as in README:

```powershell
$env:RELAY_WORKER_SHUTDOWN_GRACE = '10s'
& "$env:JAVA_HOME\bin\java.exe" -jar backend/target/relay-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
```

Use Ctrl+C for an ordinary interactive shutdown. A deployment should request a normal JVM shutdown, then allow its stop timeout. A force-kill, crash or machine loss bypasses this drain and relies on lease recovery. OS signal delivery and container stop behavior depend on the host and are not demonstrated by the context-close tests below.

## Outcomes and limits

Natural failures that finish during grace retain their usual retry policy. Forced cancellation is not written as a confirmed receiver failure: a task that has not begun completion leaves its existing attempt unfinished. After its lease expires, a surviving/replacement worker records `UNKNOWN` and schedules another bounded claim, or exhausts the existing run budget. Recovery is not immediate and is not a budget reset.

The receiver may already have processed an abandoned request. It can receive the same event/delivery again with a different attempt ID and higher generation. Fencing protects database state and does not undo receiver effects. Use durable receiver-side deduplication where business effects require it; the sample's in-memory deduplication resets on restart.

Java interruption cannot forcibly stop every native DNS/JDBC operation or third-party task. Fixed daemon pools and the admission/continuation gates bound late work; a task can run after the worker's cancellation wait, and an already-entered database transaction may finish later. The worker's wait bound is not a hard upper bound on complete Spring context or JVM exit, which can involve other resources and shutdown hooks.

## Verification

`backend/mvnw.cmd verify` includes controlled lifecycle unit tests for normal draining, deadline cancellation with interruption-ignoring code, late claim results, repeated/concurrent callbacks, stop before startup, restart rejection and unused capacity. Processor tests cover cancellation before payload loading, before HTTP and before result persistence. Configuration tests verify exact bounds and binding.

Two PostgreSQL/HTTP integration tests close real worker application contexts. One holds a receiver response, starts shutdown and proves context closure waits while a new pending event is not admitted; releasing the response persists success. The other expires a short shutdown grace, verifies untouched `IN_PROGRESS` metadata, forces the test lease to expire, recovers it as `UNKNOWN`, and proves a replacement succeeds while the old claim cannot overwrite either history or the newer success. These tests use latches and direct test-database time changes rather than sleeping through retry backoff. Existing packaged-process crash/replacement tests remain in the full regression suite.

Reference: [Spring SmartLifecycle shutdown callbacks and ordering](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html). Boot's pinned configuration metadata supplies its 30-second default; framework-only defaults need not match it.
