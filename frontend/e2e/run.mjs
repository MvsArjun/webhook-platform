import { spawn, execFile } from 'node:child_process'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { readFile, access } from 'node:fs/promises'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import pg from 'pg'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.resolve(frontend, '..')
const children = []
let database
let receiver
let cleaning = false
let cleanupTask
let ownsDatabase = false
let stage = 'configuration'
const applicationTables = ['delivery_attempts', 'deliveries', 'events', 'endpoint_signing_keys', 'api_keys', 'endpoints', 'workspaces', 'accounts']

function active() { if (cleaning) throw new Error('Harness is stopping.') }
function systemEnvironment() {
  return Object.fromEntries(Object.entries(process.env).filter(([name]) =>
    /^(PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|USERPROFILE|APPDATA|LOCALAPPDATA|HOMEDRIVE|HOMEPATH|PROCESSOR_ARCHITECTURE|NUMBER_OF_PROCESSORS)$/i.test(name)))
}

function required(name) {
  const value = process.env[name]
  if (!value) throw new Error(`Required E2E setting missing: ${name}`)
  return value
}

function dedicatedDatabase() {
  if (process.env.E2E_DB_ALLOW_DESTRUCTIVE !== 'true') throw new Error('Explicit dedicated-database cleanup consent is required.')
  const jdbc = required('E2E_DB_URL')
  if (!jdbc.startsWith('jdbc:postgresql://')) throw new Error('An explicit PostgreSQL JDBC URL is required.')
  const url = new URL(jdbc.slice(5))
  const host = url.hostname.replace(/^\[|\]$/g, '')
  const name = decodeURIComponent(url.pathname.slice(1))
  if (!['127.0.0.1', '::1', 'localhost'].includes(host) || !url.port || url.username || url.password
      || url.search || url.hash || !/^[A-Za-z0-9_]+_e2e(?:_test)?$/.test(name)) {
    throw new Error('E2E requires an explicit loopback port and a dedicated database ending _e2e or _e2e_test.')
  }
  return { jdbc, host, port: Number(url.port), database: name, user: required('E2E_DB_USERNAME'), password: required('E2E_DB_PASSWORD') }
}

async function cleanDatabase() {
  if (!database || !ownsDatabase) return
  await database.query('BEGIN')
  try {
    for (const table of applicationTables) {
      const present = await database.query('SELECT to_regclass($1) AS name', [`public.${table}`])
      if (present.rows[0].name) await database.query(`DELETE FROM public.${table}`)
    }
    await database.query('COMMIT')
  } catch (error) { await database.query('ROLLBACK'); throw error }
}

async function emptyDedicatedDatabase() {
  const catalog = await database.query("SELECT schemaname, tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema') AND schemaname NOT LIKE 'pg_toast%'")
  for (const table of catalog.rows) {
    if (table.schemaname !== 'public' || ![...applicationTables, 'flyway_schema_history'].includes(table.tablename)) {
      throw new Error('Unexpected table in the dedicated database.')
    }
    if (table.tablename !== 'flyway_schema_history') {
      const rows = await database.query(`SELECT EXISTS (SELECT 1 FROM public.${table.tablename}) AS present`)
      if (rows.rows[0].present) throw new Error('Dedicated database must be empty before the run.')
    }
  }
  active()
  ownsDatabase = true
}

function owned(name, executable, args, env = process.env, inheritedOutput = false) {
  active()
  const child = spawn(executable, args, {
    cwd: frontend, env, windowsHide: true, detached: process.platform !== 'win32',
    stdio: inheritedOutput ? ['ignore', 'inherit', 'inherit'] : 'ignore',
  })
  const entry = { name, child, exited: false, failed: false }
  entry.done = new Promise(resolve => {
    child.once('error', () => { entry.failed = true; entry.exited = true; resolve(1) })
    child.once('exit', code => { entry.exited = true; resolve(code ?? 1) })
  })
  children.push(entry)
  return entry
}

async function stop(entry) {
  if (entry.exited || !entry.child.pid) return
  if (process.platform === 'win32') {
    await new Promise(resolve => {
      const killer = spawn('taskkill.exe', ['/PID', String(entry.child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
      killer.once('error', resolve)
      killer.once('exit', resolve)
    })
  } else {
    try { process.kill(-entry.child.pid, 'SIGTERM') } catch { /* It may already have exited. */ }
    await Promise.race([entry.done, delay(2000)])
    if (!entry.exited) { try { process.kill(-entry.child.pid, 'SIGKILL') } catch { /* Already stopped. */ } }
  }
  await Promise.race([entry.done, delay(3000)])
}

function cleanup() {
  if (cleanupTask) return cleanupTask
  cleaning = true
  cleanupTask = (async () => {
    for (const child of [...children].reverse()) await stop(child)
    if (receiver) await new Promise(resolve => { receiver.closeAllConnections(); receiver.close(resolve) })
    try { await cleanDatabase() }
    finally { if (database) await database.end() }
  })()
  return cleanupTask
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(error => error ? reject(error) : resolve(port))
    })
  })
}

async function ready(url, entry, validate = () => true) {
  const deadline = Date.now() + 60000
  while (Date.now() < deadline) {
    if (entry.exited) throw new Error(`${entry.name} exited before readiness.`)
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) })
      if (response.ok && await validate(response)) return
    } catch { /* Readiness retries are bounded; child output is never recorded. */ }
    await delay(200)
  }
  throw new Error(`${entry.name} did not become ready.`)
}

async function startReceiver(controlToken) {
  active()
  const state = { status: 400, body: '<img data-e2e-inert src=x onerror="window.__relayUnsafe=1">fixture-response</img>', received: [] }
  receiver = http.createServer(async (request, response) => {
    try {
      if (request.url === '/control') {
        const token = request.headers['x-relay-e2e-control']
        if (typeof token !== 'string' || token.length !== controlToken.length
            || !timingSafeEqual(Buffer.from(token), Buffer.from(controlToken))) {
          response.writeHead(403).end(); return
        }
        if (request.method === 'GET') {
          response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ received: state.received })); return
        }
        const data = await limited(request, 2048)
        const update = JSON.parse(data.toString('utf8'))
        if (![200, 400].includes(update.status) || typeof update.body !== 'string' || update.body.length > 1024) {
          response.writeHead(400).end(); return
        }
        state.status = update.status; state.body = update.body
        response.writeHead(204).end(); return
      }
      if (request.url !== '/receiver' || request.method !== 'POST') { response.writeHead(404).end(); return }
      await limited(request, 262144)
      if (state.received.length < 100) state.received.push({
        eventId: request.headers['x-relay-event-id'], attemptId: request.headers['x-relay-attempt-id'],
        generation: request.headers['x-relay-attempt'], status: state.status,
        signed: /^v1=[0-9a-f]{64}$/.test(request.headers['x-relay-signature'] ?? '')
          && /^[0-9]+$/.test(request.headers['x-relay-timestamp'] ?? ''),
      })
      response.writeHead(state.status, { 'Content-Type': 'text/html; charset=utf-8' }).end(state.body)
    } catch { if (!response.headersSent) response.writeHead(400); response.end() }
  })
  receiver.requestTimeout = 5000
  receiver.headersTimeout = 5000
  await new Promise(resolve => receiver.listen(0, '127.0.0.1', resolve))
  return `http://127.0.0.1:${receiver.address().port}`
}

async function limited(stream, limit) {
  let length = 0
  const chunks = []
  for await (const chunk of stream) {
    length += chunk.length
    if (length > limit) throw new Error('Fixture input limit exceeded.')
    chunks.push(chunk)
  }
  return Buffer.concat(chunks, length)
}

async function main() {
  const settings = dedicatedDatabase()
  const pinnedNode = (await readFile(path.join(root, '.node-version'), 'utf8')).trim()
  if (process.versions.node !== pinnedNode) throw new Error('Use the repository-pinned Node runtime.')
  const java = process.env.E2E_JAVA_BIN ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java')
  const version = await promisify(execFile)(java, ['-version'], { env: systemEnvironment(), windowsHide: true, timeout: 5000, maxBuffer: 4096 })
  if (!/version "21\./.test(version.stderr)) throw new Error('Java 21 is required; set JAVA_HOME or E2E_JAVA_BIN.')
  const jar = path.resolve(process.env.E2E_JAR ?? path.join(root, 'backend/target/relay-0.0.1-SNAPSHOT.jar'))
  await access(jar)
  await access(path.join(frontend, 'dist/index.html'))
  stage = 'dedicated database setup'
  database = new pg.Client({ ...settings, connectionTimeoutMillis: 5000, statement_timeout: 5000, query_timeout: 5000 })
  await database.connect()
  await emptyDedicatedDatabase()
  active()
  const controlToken = randomBytes(32).toString('hex')
  const receiverOrigin = await startReceiver(controlToken)
  const apiPort = await freePort()
  const uiPort = await freePort()
  const apiOrigin = `http://127.0.0.1:${apiPort}`
  const uiOrigin = `http://127.0.0.1:${uiPort}`
  // Only operating-system essentials are inherited: ambient Spring/JVM options cannot redirect the validated database.
  const javaEnvironment = {
    ...systemEnvironment(), SPRING_CONFIG_LOCATION: 'classpath:/', DB_URL: settings.jdbc, DB_USERNAME: settings.user, DB_PASSWORD: settings.password,
    RELAY_SIGNING_MASTER_KEY: randomBytes(32).toString('base64'),
    RELAY_DELIVERY_ALLOW_LOCAL_RECEIVER: 'true', RELAY_DELIVERY_LOCAL_RECEIVER_URL: `${receiverOrigin}/receiver`,
    RELAY_RETRY_MAX_ATTEMPTS: '1', RELAY_WORKER_CONCURRENCY: '1', RELAY_WORKER_POLL_INTERVAL: '50ms',
    RELAY_WORKER_REQUEST_TIMEOUT: '2s', RELAY_WORKER_CONNECT_TIMEOUT: '500ms', RELAY_WORKER_DNS_TIMEOUT: '500ms',
    RELAY_WORKER_LEASE: '10s', RELAY_WORKER_LEASE_SAFETY: '1s',
  }
  stage = 'API startup'
  const api = owned('API', java, ['-Xms64m', '-Xmx256m', '-jar', jar], { ...javaEnvironment, PORT: String(apiPort), SPRING_PROFILES_ACTIVE: 'development' })
  await ready(`${apiOrigin}/api/system/status`, api, async response => (await response.json()).database === 'connected')
  stage = 'worker startup'
  const worker = owned('Worker', java, ['-Xms64m', '-Xmx256m', '-jar', jar], { ...javaEnvironment, SPRING_PROFILES_ACTIVE: 'worker,development' })
  await delay(1500)
  if (worker.exited) throw new Error('Worker exited before the browser journey.')
  stage = 'dashboard startup'
  const ui = owned('Dashboard', process.execPath, [path.join(frontend, 'node_modules/vite/bin/vite.js'), 'preview', '--host', '127.0.0.1', '--port', String(uiPort), '--strictPort'],
    { ...process.env, E2E_API_TARGET: apiOrigin })
  await ready(uiOrigin, ui)
  console.log(`Relay E2E preview: ${uiOrigin}`)
  stage = 'browser journeys'
  const runner = owned('Browser tests', process.execPath, [path.join(frontend, 'node_modules/playwright/cli.js'), 'test', '--config', 'playwright.config.ts'], {
    ...process.env, E2E_UI_URL: uiOrigin, E2E_API_URL: apiOrigin,
    E2E_RECEIVER_URL: `${receiverOrigin}/receiver`, E2E_CONTROL_URL: `${receiverOrigin}/control`, E2E_CONTROL_TOKEN: controlToken,
    PLAYWRIGHT_BROWSERS_PATH: process.env.PLAYWRIGHT_BROWSERS_PATH ?? path.join(root, 'work/playwright-browsers'),
  }, true)
  const result = await runner.done
  if (worker.exited) throw new Error('Worker stopped during the browser journey.')
  process.exitCode = result
}

for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
  void cleanup().finally(() => process.exit(130))
})

try { await main() }
catch { console.error(`E2E harness failed during ${stage}; child logs and credentials are deliberately not printed.`); process.exitCode = 1 }
finally {
  try { await cleanup() }
  catch { console.error('E2E dedicated-database cleanup failed.'); process.exitCode = 1 }
}
