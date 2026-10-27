import { execFile } from 'node:child_process'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { access, readFile, stat } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { promisify } from 'node:util'
import pg from 'pg'
import { parseSettings } from '../bench/config.mjs'
import { TABLES, UUID, Session, checkJava, freePort, jsonRequest, now, ready,
  requireCondition as check, spawnOwned, stopOwned, systemEnvironment } from '../bench/support.mjs'
import { startFailureReceiver } from './receiver.mjs'

export const DEMO_SETTINGS = Object.freeze({ events: 2, payloadBytes: 1024, workerConcurrency: 1,
  workspaceConcurrency: 4, pollIntervalMs: 50, requestTimeoutMs: 5000, connectTimeoutMs: 2000,
  dnsTimeoutMs: 1000, leaseMs: 30000, leaseSafetyMs: 1000, maxAttempts: 5,
  retryBaseDelayMs: 1000, retryMaxDelayMs: 300000, scenarioDeadlineMs: 90000,
  jvmInitialHeapMiB: 64, jvmMaximumHeapMiB: 256, hikariMaximumPoolSize: 5,
  signing: true, metricsEnabled: false, logging: 'ECS INFO; child output discarded after bounded worker readiness',
  transport: 'literal-loopback HTTP development exception', ambientPgOptionsIgnored: true,
  recovery: 'natural PostgreSQL-clock lease expiry; no ledger/time mutation',
  receiverDeduplication: 'in-memory event ID plus matching payload digest; resets on receiver restart' })

export class FailureRuntime {
  constructor(root) {
    this.root = root
    this.children = []
    this.stopping = false
    this.ownsDatabase = false
    this.cleanupResult = { ownershipGranted: false, processesStopped: false, receiverClosed: false,
      fixturesRemoved: false, databaseClosed: false }
  }

  active() { check(!this.stopping) }
  healthy(worker) { this.active(); check(this.api && !this.api.exited && (!worker || !worker.exited)) }
  async query(text, values = []) {
    return this.database.query({ text, values, query_timeout: 5000 })
  }

  async initialize(onStage) {
    const { database } = parseSettings([], process.env)
    this.java = process.env.BENCH_JAVA_BIN ?? (process.env.JAVA_HOME
      ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java')
    this.jar = path.resolve(process.env.BENCH_JAR ?? path.join(this.root, 'backend/target/relay-0.0.1-SNAPSHOT.jar'))
    await access(this.jar)
    const artifact = await stat(this.jar)
    check(artifact.isFile() && artifact.size > 0 && artifact.size <= 512 * 1024 * 1024)
    onStage('database_guard')
    delete process.env.PGOPTIONS
    this.database = new pg.Client({ ...database, ssl: false, connectionTimeoutMillis: 5000,
      statement_timeout: 5000, lock_timeout: 2000, query_timeout: 5000 })
    this.database.on('error', () => {})
    await this.database.connect()
    const lock = await this.query('SELECT pg_try_advisory_lock(1919246969,1650814563) AS locked')
    check(lock.rows[0].locked === true)
    const schemas = await this.query("SELECT nspname FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND left(nspname,3) <> 'pg_'")
    check(schemas.rows.length === 0)
    const catalog = await this.query("SELECT schemaname,tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') AND schemaname NOT LIKE 'pg_toast%'")
    for (const table of catalog.rows) {
      check(table.schemaname === 'public' && [...TABLES, 'flyway_schema_history'].includes(table.tablename))
      if (table.tablename !== 'flyway_schema_history') {
        check((await this.query(`SELECT EXISTS (SELECT 1 FROM public.${table.tablename}) AS present`)).rows[0].present === false)
      }
    }
    this.active()
    this.ownsDatabase = true
    this.cleanupResult.ownershipGranted = true
    onStage('environment')
    check(process.versions.node === (await readFile(path.join(this.root, '.node-version'), 'utf8')).trim())
    const java = await checkJava(this.java, (await readFile(path.join(this.root, '.java-version'), 'utf8')).trim())
    const postgres = (await this.query(`SELECT current_setting('server_version') AS version,
      current_setting('server_version_num')::int AS version_num, current_setting('fsync') AS fsync,
      current_setting('synchronous_commit') AS synchronous_commit, current_setting('full_page_writes') AS full_page_writes,
      pg_is_in_recovery() AS recovery`)).rows[0]
    check(postgres.version_num === 170011 && postgres.fsync === 'on' && postgres.synchronous_commit === 'on'
      && postgres.full_page_writes === 'on' && postgres.recovery === false)
    const hash = createHash('sha256')
    for await (const chunk of createReadStream(this.jar)) hash.update(chunk)
    this.environment = { os: { platform: os.platform(), release: os.release(), architecture: os.arch() },
      cpu: { model: os.cpus()[0].model.trim(), logicalCores: os.cpus().length }, ramBytes: os.totalmem(),
      node: process.versions.node, java, postgres: { version: postgres.version, fsync: postgres.fsync,
        synchronousCommit: postgres.synchronous_commit, fullPageWrites: postgres.full_page_writes }, jarSha256: hash.digest('hex') }
    this.active()
    const receiver = await startFailureReceiver()
    if (this.stopping) {
      try { if (!await receiver.close()) this.cleanupResult.receiverClosed = false }
      catch { this.cleanupResult.receiverClosed = false }
      throw new Error('Demonstration is stopping.')
    }
    this.receiver = receiver
    this.active()
    const port = await freePort()
    this.origin = `http://127.0.0.1:${port}`
    this.shared = { ...systemEnvironment(), SPRING_CONFIG_LOCATION: 'classpath:/', DB_URL: database.jdbc,
      DB_USERNAME: database.user, DB_PASSWORD: database.password, RELAY_SIGNING_MASTER_KEY: randomBytes(32).toString('base64'),
      RELAY_DELIVERY_ALLOW_LOCAL_RECEIVER: 'true', RELAY_DELIVERY_LOCAL_RECEIVER_URL: receiver.url,
      RELAY_WORKER_CONCURRENCY: '1', RELAY_DELIVERY_WORKSPACE_CONCURRENCY_LIMIT: '4', RELAY_WORKER_POLL_INTERVAL: '50ms',
      RELAY_WORKER_REQUEST_TIMEOUT: '5s', RELAY_WORKER_CONNECT_TIMEOUT: '2s', RELAY_WORKER_DNS_TIMEOUT: '1s',
      RELAY_WORKER_LEASE: '30s', RELAY_WORKER_LEASE_SAFETY: '1s', RELAY_RETRY_MAX_ATTEMPTS: '5',
      RELAY_RETRY_BASE_DELAY: '1s', RELAY_RETRY_MAX_DELAY: '5m', RELAY_DELIVERY_RESPONSE_CAPTURE_BYTES: '4096',
      RELAY_OPERATIONS_ENABLED: 'false', MANAGEMENT_METRICS_ENABLE_ALL: 'false', LOGGING_STRUCTURED_FORMAT_CONSOLE: 'ecs',
      LOGGING_LEVEL_ROOT: 'INFO', SERVER_ADDRESS: '127.0.0.1', SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: '5',
      SERVER_SERVLET_SESSION_COOKIE_SECURE: 'false' }
    this.args = ['-Xms64m', '-Xmx256m', '-XX:+UseG1GC', '-jar', this.jar]
    onStage('api_startup')
    this.active()
    this.api = spawnOwned(this.java, this.args, { ...this.shared, PORT: String(port), SPRING_PROFILES_ACTIVE: 'development' }, this.root)
    this.children.push(this.api)
    await ready(this.api, async () => {
      const result = await jsonRequest(this.origin, '/api/system/status', { timeout: 1000 })
      return result.response.status === 200 && result.value?.service === 'relay' && result.value?.role === 'api'
        && result.value?.phase === 7 && result.value?.database === 'connected'
    })
    this.active()
    onStage('bootstrap')
    await this.bootstrap()
  }

  async bootstrap() {
    const session = new Session(this.origin)
    await session.refreshCsrf()
    const email = `failure-demo-${randomUUID()}@example.test`, password = `Demo!${randomBytes(24).toString('base64url')}`
    let result = await session.request('POST', '/api/auth/register', { email, password })
    check(result.response.status === 201 && UUID.test(result.value?.id ?? ''))
    result = await session.request('POST', '/api/auth/login', new URLSearchParams({ email, password }).toString(), 'application/x-www-form-urlencoded')
    check(result.response.status === 204)
    await session.refreshCsrf()
    result = await session.request('POST', '/api/workspaces', { name: 'Synthetic failure demonstration' })
    check(result.response.status === 201 && UUID.test(result.value?.id ?? '') && result.value.deliveryConcurrencyLimit === 4)
    const workspaceId = result.value.id
    result = await session.request('POST', `/api/workspaces/${workspaceId}/endpoints`, { url: this.receiver.url })
    check(result.response.status === 201 && UUID.test(result.value?.id ?? ''))
    const endpointId = result.value.id
    result = await session.request('POST', `/api/workspaces/${workspaceId}/api-keys`, { name: 'Synthetic failure client' })
    check(result.response.status === 201 && /^rly_[0-9a-f]{32}\.[A-Za-z0-9_-]{43}$/.test(result.value?.key ?? ''))
    const apiKey = result.value.key
    result = await session.request('POST', `/api/workspaces/${workspaceId}/endpoints/${endpointId}/signing-secret`)
    check(result.response.status === 200 && typeof result.value?.secret === 'string')
    const key = Buffer.from(result.value.secret, 'base64')
    check(key.length === 32 && key.toString('base64') === result.value.secret)
    this.receiver.state.secret = key
    result = await session.request('POST', '/api/auth/logout')
    check(result.response.status === 204)
    session.cookie = null; session.csrf = null
    this.owner = { workspaceId, endpointId, apiKey }
  }

  async startWorker(deadline = now() + 60000) {
    this.healthy()
    const worker = spawnOwned(this.java, this.args, { ...this.shared, SPRING_PROFILES_ACTIVE: 'worker,development' }, this.root, true)
    this.children.push(worker)
    const limit = Math.min(deadline, now() + 60000)
    while (now() < limit) {
      this.healthy(worker)
      if (worker.ready) return worker
      await delay(50)
    }
    throw new Error('Demonstration worker readiness deadline exceeded.')
  }

  async forceCrash(worker) {
    this.healthy(worker)
    check(this.children.includes(worker) && Number.isSafeInteger(worker.child.pid))
    if (process.platform === 'win32') {
      await promisify(execFile)('taskkill.exe', ['/PID', String(worker.child.pid), '/T', '/F'],
        { env: systemEnvironment(), windowsHide: true, timeout: 5000, maxBuffer: 4096 })
    } else process.kill(-worker.child.pid, 'SIGKILL')
    await Promise.race([worker.done, delay(5000)])
    check(worker.exited)
  }

  async cleanup() {
    if (this.cleanupPromise) return this.cleanupPromise
    this.stopping = true
    this.cleanupPromise = (async () => {
      const stopped = []
      for (const child of [...this.children].reverse()) stopped.push(await stopOwned(child))
      this.cleanupResult.processesStopped = stopped.every(Boolean)
      try { this.cleanupResult.receiverClosed = this.receiver ? await this.receiver.close() : true } catch {}
      if (this.owner) this.owner.apiKey = null
      if (this.database) {
        try {
          if (this.ownsDatabase && this.cleanupResult.processesStopped) {
            await this.query('BEGIN')
            await this.query("SET LOCAL statement_timeout='10s'")
            await this.query("SET LOCAL lock_timeout='2s'")
            const removals = TABLES.map(table => `IF to_regclass('public.${table}') IS NOT NULL THEN DELETE FROM public.${table}; END IF;`).join('\n')
            await this.database.query({ text: `DO $cleanup$ BEGIN ${removals} END; $cleanup$;`, query_timeout: 12000 })
            await this.query('COMMIT')
            this.cleanupResult.fixturesRemoved = true
          }
        } catch { try { await this.query('ROLLBACK') } catch {} }
        finally {
          this.cleanupResult.databaseClosed = await Promise.race([this.database.end().then(() => true).catch(() => false), delay(3000).then(() => false)])
          if (!this.cleanupResult.databaseClosed) this.database.connection?.stream?.destroy()
        }
      } else this.cleanupResult.databaseClosed = true
      return this.cleanupResult
    })()
    return this.cleanupPromise
  }
}
