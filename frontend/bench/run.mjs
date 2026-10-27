import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { access, mkdir, readFile, realpath, stat, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import pg from 'pg'
import { parseSettings } from './config.mjs'
import { distribution, makePayload } from './measurements.mjs'
import { UUID, TABLES, BATCH_TIMEOUT_MS, OBSERVATION_INTERVAL_MS, Session, checkJava, freePort,
  jsonRequest, now, ready, remaining, requireCondition, responseJson, spawnOwned, startReceiver,
  stopOwned, systemEnvironment } from './support.mjs'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.resolve(frontend, '..')
const reportDirectory = path.join(root, 'work', 'bench')
const LOCK_KEYS = [1919246969, 1650814563]
const STATES = ['PENDING', 'IN_FLIGHT', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED']
const OUTCOMES = ['IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'UNKNOWN']
const children = []
let database, receiver, settings, apiEntry, workerEntry
let ownsDatabase = false, stopping = false, cleanupPromise, sequence = 0, stage = 'configuration'
let stopSignal = false
const report = { schemaVersion: 1, result: 'failure', startedAt: new Date().toISOString(),
  environment: null, settings: null, warmup: null, batches: [], cleanup: { processesStopped: false,
    receiverClosed: false, fixturesRemoved: false, databaseClosed: false, ownershipGranted: false } }

function active() { requireCondition(!stopping) }
function safeNumber(value) { const result = Number(value); requireCondition(Number.isFinite(result) && result >= 0); return result }
function counts(labels) { return Object.fromEntries(labels.map(label => [label, 0])) }
function assertHealthyProcesses() { active(); requireCondition(!apiEntry.exited && !workerEntry.exited) }
function safeText(value, maximum = 160) {
  requireCondition(typeof value === 'string' && value.length > 0 && value.length <= maximum && /^[\x20-\x7e]+$/.test(value))
  return value
}
async function query(text, values = [], deadline = now() + 5000) {
  return database.query({ text, values, query_timeout: remaining(deadline, 5000) })
}

async function emptyDatabase() {
  const lock = await query('SELECT pg_try_advisory_lock($1::int, $2::int) AS locked', LOCK_KEYS)
  requireCondition(lock.rows[0].locked === true)
  const schemas = await query("SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND left(nspname,3) <> 'pg_'")
  requireCondition(schemas.rows.length === 0)
  const catalog = await query("SELECT schemaname,tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') AND schemaname NOT LIKE 'pg_toast%'")
  for (const table of catalog.rows) {
    requireCondition(table.schemaname === 'public' && [...TABLES, 'flyway_schema_history'].includes(table.tablename))
    if (table.tablename !== 'flyway_schema_history') {
      const rows = await query(`SELECT EXISTS (SELECT 1 FROM public.${table.tablename}) AS present`)
      requireCondition(rows.rows[0].present === false)
    }
  }
  active()
  ownsDatabase = true
  report.cleanup.ownershipGranted = true
}

async function environment(java, jar) {
  const nodeVersion = (await readFile(path.join(root, '.node-version'), 'utf8')).trim()
  const javaVersion = (await readFile(path.join(root, '.java-version'), 'utf8')).trim()
  requireCondition(process.versions.node === nodeVersion)
  const javaDetails = await checkJava(java, javaVersion)
  const jarFile = await stat(jar)
  requireCondition(jarFile.isFile() && jarFile.size > 0 && jarFile.size <= 512 * 1024 * 1024)
  const digest = createHash('sha256')
  for await (const chunk of createReadStream(jar)) digest.update(chunk)
  const postgres = (await query(`SELECT current_setting('server_version') AS version,
    current_setting('server_version_num')::int AS version_num, current_setting('fsync') AS fsync,
    current_setting('synchronous_commit') AS synchronous_commit,
    current_setting('full_page_writes') AS full_page_writes, current_setting('max_connections')::int AS max_connections,
    current_setting('shared_buffers') AS shared_buffers, pg_is_in_recovery() AS recovery`)).rows[0]
  requireCondition(postgres.version_num === 170011 && postgres.fsync === 'on' && postgres.synchronous_commit === 'on'
    && postgres.full_page_writes === 'on' && postgres.recovery === false)
  const processors = os.cpus()
  requireCondition(processors.length > 0)
  return { os: { platform: os.platform(), release: safeText(os.release()), architecture: os.arch() },
    cpu: { model: safeText(processors[0].model), logicalCores: processors.length }, ramBytes: os.totalmem(),
    node: nodeVersion, java: javaDetails, postgres: { version: safeText(postgres.version),
      fsync: postgres.fsync, synchronousCommit: postgres.synchronous_commit, fullPageWrites: postgres.full_page_writes,
      maxConnections: safeNumber(postgres.max_connections), sharedBuffers: safeText(postgres.shared_buffers) },
    jarSha256: digest.digest('hex') }
}

async function bootstrap(origin, receiverUrl) {
  const session = new Session(origin)
  await session.refreshCsrf()
  const email = `bench-${randomUUID()}@example.test`, password = `Benchmark!${randomBytes(24).toString('base64url')}`
  let result = await session.request('POST', '/api/auth/register', { email, password })
  requireCondition(result.response.status === 201 && UUID.test(result.value?.id ?? ''))
  result = await session.request('POST', '/api/auth/login', new URLSearchParams({ email, password }).toString(),
    'application/x-www-form-urlencoded')
  requireCondition(result.response.status === 204)
  await session.refreshCsrf()
  result = await session.request('POST', '/api/workspaces', { name: 'Synthetic benchmark' })
  requireCondition(result.response.status === 201 && UUID.test(result.value?.id ?? '') && result.value.deliveryConcurrencyLimit === 4)
  const workspaceId = result.value.id
  result = await session.request('POST', `/api/workspaces/${workspaceId}/endpoints`, { url: receiverUrl })
  requireCondition(result.response.status === 201 && UUID.test(result.value?.id ?? ''))
  const endpointId = result.value.id
  result = await session.request('POST', `/api/workspaces/${workspaceId}/api-keys`, { name: 'Synthetic benchmark client' })
  requireCondition(result.response.status === 201 && /^rly_[0-9a-f]{32}\.[A-Za-z0-9_-]{43}$/.test(result.value?.key ?? ''))
  const apiKey = result.value.key
  result = await session.request('POST', `/api/workspaces/${workspaceId}/endpoints/${endpointId}/signing-secret`)
  requireCondition(result.response.status === 200 && typeof result.value?.secret === 'string')
  const key = Buffer.from(result.value.secret, 'base64')
  requireCondition(key.length === 32 && key.toString('base64') === result.value.secret)
  receiver.state.secret = key
  // Logout ends the management session; ingestion uses only the disposable workspace bearer.
  result = await session.request('POST', '/api/auth/logout')
  requireCondition(result.response.status === 204)
  session.cookie = null; session.csrf = null
  return { workspaceId, endpointId, apiKey }
}

function receiverSummary(before, cohort) {
  const result = Object.fromEntries(Object.entries(receiver.state.counters).map(([name, value]) =>
    [name, name === 'peakInFlight' ? value : value - before[name]]))
  result.uniqueEvents = [...cohort.keys()].filter(id => receiver.state.receipts.has(id)).length
  return result
}

async function cohortProgress(owner, cohort, deadline) {
  const progress = (await query(`WITH selected AS (
      SELECT id,status FROM deliveries WHERE workspace_id=$1 AND event_id=ANY($2::uuid[])
    ), delivery_counts AS (
      SELECT count(*)::int AS delivery_count,
        count(*) FILTER (WHERE status='PENDING')::int AS pending,
        count(*) FILTER (WHERE status='IN_FLIGHT')::int AS in_flight,
        count(*) FILTER (WHERE status='RETRY_WAIT')::int AS retry_wait,
        count(*) FILTER (WHERE status='SUCCEEDED')::int AS succeeded,
        count(*) FILTER (WHERE status='FAILED')::int AS failed FROM selected
    ), attempt_counts AS (
      SELECT count(*)::int AS attempt_count,
        count(*) FILTER (WHERE a.outcome='IN_PROGRESS')::int AS attempt_in_progress,
        count(*) FILTER (WHERE a.outcome='SUCCEEDED')::int AS attempt_succeeded,
        count(*) FILTER (WHERE a.outcome='FAILED')::int AS attempt_failed,
        count(*) FILTER (WHERE a.outcome='UNKNOWN')::int AS attempt_unknown
      FROM delivery_attempts a JOIN selected d ON d.id=a.delivery_id WHERE a.workspace_id=$1
    ) SELECT (SELECT count(*)::int FROM events WHERE workspace_id=$1 AND id=ANY($2::uuid[])) AS event_count,
      delivery_counts.*,attempt_counts.* FROM delivery_counts CROSS JOIN attempt_counts`,
  [owner.workspaceId, [...cohort.keys()]], deadline)).rows[0]
  return { eventCount: progress.event_count, deliveryCount: progress.delivery_count,
    deliveryStates: Object.fromEntries(STATES.map(status => [status, progress[status.toLowerCase()]])),
    attemptOutcomes: Object.fromEntries(OUTCOMES.map(outcome => [outcome, progress[`attempt_${outcome.toLowerCase()}`]])),
    attemptCount: progress.attempt_count }
}

async function cohortDetails(owner, cohort, deadline) {
  const ids = [...cohort.keys()]
  const rows = (await query(`SELECT e.id::text AS event_id, e.endpoint_id::text AS endpoint_id,
      d.id::text AS delivery_id, d.status, d.claim_generation, d.run_attempts, d.replay_generation,
      count(a.id)::int AS attempts, max(a.id::text) AS attempt_id,
      count(a.id) FILTER (WHERE a.outcome='IN_PROGRESS')::int AS attempt_in_progress,
      count(a.id) FILTER (WHERE a.outcome='SUCCEEDED')::int AS attempt_succeeded,
      count(a.id) FILTER (WHERE a.outcome='FAILED')::int AS attempt_failed,
      count(a.id) FILTER (WHERE a.outcome='UNKNOWN')::int AS attempt_unknown,
      count(a.id) FILTER (WHERE a.outcome='SUCCEEDED' AND a.http_status=204 AND a.failure_code IS NULL
        AND a.finished_at IS NOT NULL AND a.claim_generation=1 AND a.run_attempt=1 AND a.replay_generation=0)::int AS healthy,
      EXTRACT(EPOCH FROM (min(a.started_at)-e.accepted_at))*1000 AS first_start_ms,
      EXTRACT(EPOCH FROM (max(a.finished_at)-e.accepted_at))*1000 AS finished_ms,
      max(a.duration_ms) AS transport_ms
    FROM events e LEFT JOIN deliveries d ON d.workspace_id=e.workspace_id AND d.event_id=e.id
    LEFT JOIN delivery_attempts a ON a.workspace_id=d.workspace_id AND a.delivery_id=d.id
    WHERE e.workspace_id=$1 AND e.id=ANY($2::uuid[])
    GROUP BY e.id,e.endpoint_id,d.id LIMIT $3`, [owner.workspaceId, ids, ids.length + 1], deadline)).rows
  const stateCounts = counts(STATES), outcomeCounts = counts(OUTCOMES)
  for (const row of rows) {
    if (Object.hasOwn(stateCounts, row.status)) stateCounts[row.status]++
    for (const outcome of OUTCOMES) outcomeCounts[outcome] += Number(row[`attempt_${outcome.toLowerCase()}`])
  }
  return { rows, stateCounts, outcomeCounts }
}

async function batch(owner, size, iteration) {
  const summary = { iteration, status: size ? 'running' : 'skipped', planned: size, attempted: 0, accepted: 0,
    acceptance: { statusCounts: {}, clientErrors: 0, elapsedMs: null, throughputPerSecond: null, roundTripMs: distribution([]) },
    drain: { elapsedFromFirstSendMs: null, elapsedAfterLast202Ms: null, throughputPerSecond: null },
    ledger: { eventCount: 0, deliveryCount: 0, deliveryStates: counts(STATES), attemptOutcomes: counts(OUTCOMES), attemptCount: 0 }, receiver: null,
    storedAcceptedAtToFirstStartedAtMs: distribution([]), storedAcceptedAtToFinishedAtMs: distribution([]),
    persistedTransportDurationMs: distribution([]) }
  if (iteration === 0) report.warmup = summary
  else report.batches.push(summary)
  if (!size) return
  const before = { ...receiver.state.counters }
  receiver.state.counters.peakInFlight = 0
  const cohort = new Map(), deliveryIds = new Set(), rtts = []
  const deadline = now() + BATCH_TIMEOUT_MS
  let index = 0, firstSend = null, last202 = null
  try {
    const clients = await Promise.allSettled(Array.from({ length: Math.min(size, settings.concurrency) }, async () => {
      while (index < size && now() < deadline) {
        assertHealthyProcesses()
        const item = index++
        if (item >= size) break
        const current = ++sequence
        receiver.state.sequences.add(current)
        const payload = makePayload(current, settings.payloadBytes)
        const started = now()
        if (firstSend === null) firstSend = started
        summary.attempted++
        try {
          const response = await fetch(`${owner.origin}/api/v1/endpoints/${owner.endpointId}/events`, {
            method: 'POST', headers: { Authorization: `Bearer ${owner.apiKey}`, 'Content-Type': 'application/json' },
            body: payload, redirect: 'error', signal: AbortSignal.timeout(remaining(deadline)),
          })
          summary.acceptance.statusCounts[response.status] = (summary.acceptance.statusCounts[response.status] ?? 0) + 1
          const value = await responseJson(response)
          const finished = now()
          if (response.status === 202) {
            requireCondition(UUID.test(value?.eventId ?? '') && UUID.test(value?.deliveryId ?? '')
              && !cohort.has(value.eventId) && !deliveryIds.has(value.deliveryId))
            cohort.set(value.eventId, { deliveryId: value.deliveryId, sequence: current })
            deliveryIds.add(value.deliveryId)
            summary.accepted++; rtts.push(finished - started); last202 = finished
          }
        } catch { summary.acceptance.clientErrors++ }
      }
    }))
    requireCondition(clients.every(client => client.status === 'fulfilled')
      && summary.attempted === size && summary.accepted === size && summary.acceptance.clientErrors === 0
      && Object.keys(summary.acceptance.statusCounts).length === 1 && summary.acceptance.statusCounts[202] === size)
    summary.acceptance.elapsedMs = last202 - firstSend
    summary.acceptance.throughputPerSecond = size * 1000 / summary.acceptance.elapsedMs
    summary.acceptance.roundTripMs = distribution(rtts)
    let observed = null
    do {
      assertHealthyProcesses()
      summary.ledger = await cohortProgress(owner, cohort, deadline)
      summary.receiver = receiverSummary(before, cohort)
      requireCondition(summary.ledger.deliveryStates.FAILED === 0 && summary.ledger.attemptOutcomes.FAILED === 0
        && summary.ledger.attemptOutcomes.UNKNOWN === 0 && summary.ledger.attemptCount <= size)
      if (summary.ledger.eventCount === size && summary.ledger.deliveryCount === size && summary.ledger.deliveryStates.SUCCEEDED === size
          && summary.ledger.attemptCount === size && summary.ledger.attemptOutcomes.SUCCEEDED === size
          && summary.receiver.responses204 === size && receiver.state.inFlight === 0) { observed = now(); break }
      await delay(Math.min(OBSERVATION_INTERVAL_MS, remaining(deadline)))
    } while (now() < deadline)
    requireCondition(observed !== null && observed < deadline)
    summary.drain = { elapsedFromFirstSendMs: observed - firstSend, elapsedAfterLast202Ms: observed - last202,
      throughputPerSecond: size * 1000 / (observed - firstSend) }
    // Detailed rows are read once, after the monotonic aggregate-success observation has ended the drain window.
    const details = await cohortDetails(owner, cohort, deadline)
    requireCondition(details.rows.length === size && details.stateCounts.SUCCEEDED === size)
    const queueSamples = [], finishSamples = [], transportSamples = []
    for (const row of details.rows) {
      const expected = cohort.get(row.event_id), receipt = receiver.state.receipts.get(row.event_id)
      requireCondition(expected && row.delivery_id === expected.deliveryId && row.endpoint_id === owner.endpointId
        && row.attempts === 1 && row.healthy === 1 && Number(row.claim_generation) === 1 && row.run_attempts === 1
        && Number(row.replay_generation) === 0 && receipt && receipt.sequence === expected.sequence
        && receipt.deliveryId === expected.deliveryId && receipt.requests === 1 && receipt.responses === 1
        && receipt.attempts.size === 1 && receipt.attempts.has(row.attempt_id)
        && receipt.generations.size === 1 && receipt.generations.has('1'))
      requireCondition(row.first_start_ms !== null && row.finished_ms !== null && row.transport_ms !== null)
      queueSamples.push(safeNumber(row.first_start_ms)); finishSamples.push(safeNumber(row.finished_ms))
      transportSamples.push(safeNumber(row.transport_ms))
    }
    summary.storedAcceptedAtToFirstStartedAtMs = distribution(queueSamples)
    summary.storedAcceptedAtToFinishedAtMs = distribution(finishSamples)
    summary.persistedTransportDurationMs = distribution(transportSamples)
    requireCondition(summary.receiver.requests === size && summary.receiver.uniqueEvents === size
      && summary.receiver.signaturesVerified === size && summary.receiver.responses204 === size
      && summary.receiver.invalidSignatures === 0 && summary.receiver.invalidHeaders === 0
      && summary.receiver.payloadMismatches === 0 && summary.receiver.duplicates === 0 && summary.receiver.requestFailures === 0)
    summary.status = 'success'
  } catch {
    summary.status = 'failure'
    summary.acceptance.roundTripMs = distribution(rtts)
    summary.receiver = receiverSummary(before, cohort)
    throw new Error('Benchmark batch failed.')
  }
}

async function verifyTotalFixtures(total) {
  const actual = (await query(`SELECT
    (SELECT count(*)::int FROM accounts) AS accounts, (SELECT count(*)::int FROM workspaces) AS workspaces,
    (SELECT count(*)::int FROM endpoints) AS endpoints, (SELECT count(*)::int FROM api_keys) AS api_keys,
    (SELECT count(*)::int FROM endpoint_signing_keys) AS endpoint_signing_keys,
    (SELECT count(*)::int FROM events) AS events, (SELECT count(*)::int FROM deliveries) AS deliveries,
    (SELECT count(*)::int FROM delivery_attempts) AS delivery_attempts`)).rows[0]
  for (const table of TABLES) requireCondition(actual[table] === (['events', 'deliveries', 'delivery_attempts'].includes(table) ? total : 1))
  requireCondition(receiver.state.receipts.size === total && receiver.state.sequences.size === total)
  report.finalCounts = { submitted: total, events: actual.events, deliveries: actual.deliveries, attempts: actual.delivery_attempts,
    receiverUniqueEvents: receiver.state.receipts.size, verifiedSignatures: receiver.state.counters.signaturesVerified,
    duplicateRequests: receiver.state.counters.duplicates }
}

async function cleanup() {
  if (cleanupPromise) return cleanupPromise
  stopping = true
  cleanupPromise = (async () => {
    const stopped = []
    for (const child of [...children].reverse()) stopped.push(await stopOwned(child))
    report.cleanup.processesStopped = stopped.every(Boolean)
    if (receiver) {
      await new Promise(resolve => { receiver.server.closeAllConnections(); receiver.server.close(resolve) })
      receiver.state.secret?.fill(0)
    }
    report.cleanup.receiverClosed = true
    if (database) {
      try {
        if (ownsDatabase && report.cleanup.processesStopped) {
          await query('BEGIN')
          await query("SET LOCAL statement_timeout = '10s'")
          await query("SET LOCAL lock_timeout = '2s'")
          const removals = TABLES.map(table => `IF to_regclass('public.${table}') IS NOT NULL THEN DELETE FROM public.${table}; END IF;`).join('\n')
          await database.query({ text: `DO $cleanup$ BEGIN ${removals} END; $cleanup$;`, query_timeout: 12000 })
          await query('COMMIT')
          report.cleanup.fixturesRemoved = true
        }
      } catch { try { await query('ROLLBACK') } catch { /* No raw persistence diagnostics. */ } }
      finally {
        const closed = await Promise.race([database.end().then(() => true).catch(() => false), delay(3000).then(() => false)])
        report.cleanup.databaseClosed = closed
        if (!closed) database.connection?.stream?.destroy()
      }
    } else report.cleanup.databaseClosed = true
  })()
  return cleanupPromise
}

async function main() {
  settings = parseSettings(process.argv.slice(2), process.env)
  const { database: ignored, ...workload } = settings
  report.settings = { ...workload, workerConcurrency: 4, workspaceConcurrency: 4, pollIntervalMs: 50,
    requestTimeoutMs: 5000, connectTimeoutMs: 2000, dnsTimeoutMs: 1000, leaseMs: 30000, leaseSafetyMs: 1000,
    maxAttempts: 5, responseCaptureBytes: 4096, batchDeadlineMs: BATCH_TIMEOUT_MS, observationIntervalMs: OBSERVATION_INTERVAL_MS,
    hikariMaximumPoolSize: { api: 5, worker: 5 },
    postgresClient: { ssl: false, ambientPgOptionsIgnored: true },
    jvm: { processes: 2, initialHeapMiB: 64, maximumHeapMiB: 256, gc: 'G1' },
    logging: { format: 'ECS JSON', level: 'INFO', sink: 'discarded; worker fixed readiness line recognized in bounded buffers' },
    metrics: { durableCollectorEnabled: false, allMetersEnabled: false },
    limitations: ['Single host; API, worker, client, receiver and PostgreSQL compete for resources.',
      'Literal-loopback HTTP development exception; no TLS or external destination network.',
      'Receiver delay is synthetic and receiver deduplication is memory-only.',
      'Stored accepted_at is ingestion transaction start; finished_at is written before completion commit.',
      'Client monotonic drain ends at aggregate committed-success plus receiver-completion observation; detailed row validation follows once.',
      'Healthy-only workload; this run does not establish capacity, fault recovery or delivery guarantees.'] }
  const java = process.env.BENCH_JAVA_BIN ?? (process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java')
  const jar = path.resolve(process.env.BENCH_JAR ?? path.join(root, 'backend', 'target', 'relay-0.0.1-SNAPSHOT.jar'))
  await access(jar)
  stage = 'database_guard'
  // pg falls back to this ambient variable even when options is an empty string. It must not
  // override the role's durability defaults while the sanitized JDBC children see different ones.
  delete process.env.PGOPTIONS
  database = new pg.Client({ ...settings.database, connectionTimeoutMillis: 5000, statement_timeout: 5000,
    lock_timeout: 5000, query_timeout: 5000, ssl: false })
  database.on('error', () => { /* Queries fail safely; never emit a driver error or supplied configuration. */ })
  await database.connect()
  await emptyDatabase()
  stage = 'environment'
  report.environment = await environment(java, jar)
  active()
  const startedReceiver = await startReceiver(settings)
  if (stopping) {
    // Cleanup may have completed while listen awaited, before this local resource had an owner.
    // Close it directly rather than relying on the already-resolved cleanup promise.
    try {
      await new Promise((resolve, reject) => {
        startedReceiver.server.closeAllConnections()
        startedReceiver.server.close(error => error ? reject(error) : resolve())
      })
    } catch { report.cleanup.receiverClosed = false }
    startedReceiver.state.secret?.fill(0)
    throw new Error('Benchmark is stopping.')
  }
  receiver = startedReceiver
  const port = await freePort(), origin = `http://127.0.0.1:${port}`
  const shared = { ...systemEnvironment(), SPRING_CONFIG_LOCATION: 'classpath:/', DB_URL: settings.database.jdbc,
    DB_USERNAME: settings.database.user, DB_PASSWORD: settings.database.password, RELAY_SIGNING_MASTER_KEY: randomBytes(32).toString('base64'),
    RELAY_DELIVERY_ALLOW_LOCAL_RECEIVER: 'true', RELAY_DELIVERY_LOCAL_RECEIVER_URL: receiver.url,
    RELAY_WORKER_CONCURRENCY: '4', RELAY_DELIVERY_WORKSPACE_CONCURRENCY_LIMIT: '4', RELAY_WORKER_POLL_INTERVAL: '50ms',
    RELAY_WORKER_REQUEST_TIMEOUT: '5s', RELAY_WORKER_CONNECT_TIMEOUT: '2s', RELAY_WORKER_DNS_TIMEOUT: '1s',
    RELAY_WORKER_LEASE: '30s', RELAY_WORKER_LEASE_SAFETY: '1s', RELAY_RETRY_MAX_ATTEMPTS: '5',
    RELAY_DELIVERY_RESPONSE_CAPTURE_BYTES: '4096', RELAY_OPERATIONS_ENABLED: 'false', MANAGEMENT_METRICS_ENABLE_ALL: 'false',
    LOGGING_STRUCTURED_FORMAT_CONSOLE: 'ecs', LOGGING_LEVEL_ROOT: 'INFO', SERVER_ADDRESS: '127.0.0.1',
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: '5',
    SERVER_SERVLET_SESSION_COOKIE_SECURE: 'false' }
  const args = ['-Xms64m', '-Xmx256m', '-XX:+UseG1GC', '-jar', jar]
  stage = 'api_startup'
  active()
  apiEntry = spawnOwned(java, args, { ...shared, PORT: String(port), SPRING_PROFILES_ACTIVE: 'development' }, frontend)
  children.push(apiEntry)
  await ready(apiEntry, async () => {
    const result = await jsonRequest(origin, '/api/system/status', { timeout: 1000 })
    return result.response.status === 200 && result.value?.service === 'relay' && result.value?.role === 'api'
      && result.value?.phase === 7 && result.value?.database === 'connected'
  })
  stage = 'bootstrap'
  const owner = { ...await bootstrap(origin, receiver.url), origin }
  stage = 'worker_startup'
  active()
  workerEntry = spawnOwned(java, args, { ...shared, SPRING_PROFILES_ACTIVE: 'worker,development' }, frontend, true)
  children.push(workerEntry)
  await ready(workerEntry, async () => workerEntry.ready)
  stage = 'warmup'
  await batch(owner, settings.warmup, 0)
  for (let iteration = 1; iteration <= settings.iterations; iteration++) {
    stage = 'measured_batch'
    await batch(owner, settings.events, iteration)
  }
  stage = 'final_validation'
  assertHealthyProcesses()
  await verifyTotalFixtures(settings.warmup + settings.iterations * settings.events)
  owner.apiKey = null
  report.result = 'success'
}

async function saveReport() {
  await mkdir(reportDirectory, { recursive: true })
  const relative = path.relative(await realpath(root), await realpath(reportDirectory))
  requireCondition(relative && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative))
  const name = `load-${new Date().toISOString().replace(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}.json`
  await writeFile(path.join(reportDirectory, name), `${JSON.stringify(report, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
}

for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
  stopSignal = true
  stopping = true
  // The ordinary finally path writes the privacy-safe failure report. A final guard bounds signal cleanup.
  void cleanup().catch(() => { /* The final path records only the fixed cleanup failure. */ })
  setTimeout(() => process.exit(130), 45000).unref()
})

try { await main() }
catch { report.result = 'failure'; report.failureStage = stage; process.exitCode = 1 }
finally {
  stage = 'cleanup'
  try { await cleanup() }
  catch { report.result = 'failure'; report.failureStage = 'cleanup'; process.exitCode = 1 }
  if (!report.cleanup.processesStopped || !report.cleanup.receiverClosed || !report.cleanup.databaseClosed
      || (ownsDatabase && !report.cleanup.fixturesRemoved) || stopSignal) {
    report.result = 'failure'; report.failureStage ??= 'cleanup'; process.exitCode = stopSignal ? 130 : 1
  }
  report.completedAt = new Date().toISOString()
  try {
    await saveReport()
    console.log(report.result === 'success' ? 'Benchmark completed; safe report written under work/bench.'
      : 'Benchmark failed; safe report written under work/bench. Raw errors and child output are suppressed.')
  } catch {
    console.error('Benchmark report could not be written; raw errors and configuration are suppressed.')
    process.exitCode = 1
  }
}
