import { spawn } from 'node:child_process'
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { access, mkdir, readFile, realpath, stat, unlink, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import pg from 'pg'
import { makePayload } from '../bench/measurements.mjs'
import { Session, UUID, TABLES, checkJava, freePort, jsonRequest, now, spawnOwned } from '../bench/support.mjs'
import { check, ownership, commandEnvironment, localDockerEndpoint, verifyCompose, verifyResources, ownedFile, quiescent } from './support.mjs'

// This verifier deliberately proves POSIX SIGTERM behavior. PowerShell's interactive flow is separate.
if (process.platform !== 'linux') {
  console.error('[Startup] Linux Docker verification is required; no resources or files were created.')
  process.exit(1)
}

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const frontend = path.join(root, 'frontend'), composeFile = path.join(root, 'compose.yml')
const reportDirectory = path.join(root, 'work/startup'), owner = ownership(randomBytes(8).toString('hex'))
const directory = path.join(reportDirectory, owner.id), envFile = path.join(directory, 'compose.env')
const masterFile = path.join(directory, 'signing-master-key.txt'), children = [], commands = new Set()
const deadline = now() + 480000
let stage = 'configuration', stopping = false, stopSignal = false, preflightPassed = false, ownsProject = false
let database, settings, dockerHost, composeHash, shared, java, args, api, worker, vite, receiver, session, account, endpoint, apiKey
let apiOrigin, uiOrigin, receiverUrl, endpointSecret, cleanupPromise, cleanupDeadline
let createdDirectory = false
const createdFiles = []
const report = { schemaVersion: 1, result: 'failure', startedAt: new Date().toISOString(), environment: null,
  settings: { composeImage: 'postgres:17.11-bookworm', databaseBinding: 'literal loopback only', frontend: 'Vite development server',
    delivery: 'application defaults; exact development-only local receiver exception', metricsEnabled: false,
    receiver: 'source-launched samples/receiver/Receiver.java', restart: 'Compose down without volume removal, then up',
    applicationStop: 'SIGTERM with 35-second grace; forced fallback invalidates success', deadlineMs: 480000,
    cleanupDeadlineMs: 120000 }, firstStartup: null, retainedRestart: null, finalCounts: null, cleanup: null }
const cleanupState = { ownershipGranted: false, ownedProcessesStopped: false, normalApplicationShutdown: false,
  ownedCommandsStopped: false, databaseClosed: false, composeRemoved: false, containersAbsent: false,
  volumeAbsent: false, temporarySecretsRemoved: false }

function active() { check(!stopping && now() < deadline) }
function remaining(maximum = 10000) { active(); return Math.max(1, Math.min(maximum, Math.floor(deadline - now()))) }
function digest(bytes) { return createHash('sha256').update(bytes).digest('hex') }
function healthy() { active(); check(!api?.exited && !worker?.exited && !vite?.exited && !receiver?.exited) }
async function bounded(task, milliseconds, fallback) {
  let timer
  try { return await Promise.race([task,new Promise(resolve=>{timer=setTimeout(()=>resolve(fallback),milliseconds)})]) }
  finally {clearTimeout(timer)}
}

function killCommand(entry) { try { process.kill(-entry.child.pid, 'SIGKILL') } catch {} }
function groupAlive(entry) {
  if (!entry.child.pid) return false
  try { process.kill(-entry.child.pid, 0); return true } catch(error) { return error.code !== 'ESRCH' }
}
async function commandGroupStopped(entry) {
  if (groupAlive(entry)) killCommand(entry)
  const until=now()+3000
  while(groupAlive(entry) && now()<until) await delay(25)
  return !groupAlive(entry)
}
async function command(executable, argv, timeout = 15000, cleaning = false) {
  if (!cleaning) active()
  const limit=cleaning?Math.min(timeout,Math.floor(cleanupDeadline-now())):remaining(timeout)
  check(limit>0)
  const child=spawn(executable,argv,{cwd:root,env:commandEnvironment(),windowsHide:true,detached:true,
    stdio:['ignore','pipe','ignore']})
  const entry={child,exited:false,failed:false}
  commands.add(entry)
  let output='',bytes=0,timedOut=false
  child.stdout.on('data',chunk=>{
    bytes+=chunk.length
    if(bytes>524288){entry.failed=true;killCommand(entry)}
    else output+=chunk.toString('utf8')
  })
  entry.done=new Promise(resolve=>{
    child.once('error',()=>{entry.failed=true;entry.exited=true;resolve(1)})
    child.once('close',code=>{entry.exited=true;resolve(code??1)})
  })
  const timer=setTimeout(()=>{timedOut=true;killCommand(entry)},limit)
  const result=await bounded(entry.done,limit+4000,null)
  clearTimeout(timer)
  const stopped=await commandGroupStopped(entry)
  if(entry.exited && stopped) commands.delete(entry)
  else {child.stdout.destroy();child.unref()}
  check(result===0 && !timedOut && !entry.failed && stopped)
  return output.trim()
}
const docker = (argv, timeout, cleaning = false) => command('docker', ['--host', dockerHost, ...argv], timeout, cleaning)
async function compose(argv, timeout, cleaning = false) {
  check(digest(await readFile(composeFile)) === composeHash)
  return docker(['compose', '--project-name', owner.project, '--env-file', envFile, '-f', composeFile, ...argv], timeout, cleaning)
}

async function inspectResources(cleaning = false) {
  const ids = (await docker(['ps', '-aq', '--filter', `label=com.docker.compose.project=${owner.project}`], 15000, cleaning))
    .split(/\s+/).filter(Boolean)
  check(ids.length <= 1 && ids.every(id => /^[0-9a-f]{12,64}$/.test(id)))
  const containers = ids.length ? JSON.parse(await docker(['inspect', ...ids], 15000, cleaning)) : []
  const volumes = (await docker(['volume', 'ls', '-q'], 15000, cleaning)).split(/\s+/).filter(name=>name===owner.volume)
  check(volumes.length <= 1 && volumes.every(name => name === owner.volume))
  const volume = volumes.length ? JSON.parse(await docker(['volume', 'inspect', owner.volume], 15000, cleaning))[0] : null
  verifyResources(owner, containers, volume)
  return { containers, volume }
}

async function query(text, values = []) { return database.query({ text, values, query_timeout: remaining(5000) }) }
async function connectDatabase() {
  active()
  delete process.env.PGOPTIONS
  database = new pg.Client({ host: '127.0.0.1', port: settings.port, database: settings.database,
    user: settings.user, password: settings.password, ssl: false, options: '',
    connectionTimeoutMillis: remaining(5000), statement_timeout: 5000, lock_timeout: 2000, query_timeout: 5000 })
  database.on('error', () => {})
  await database.connect()
  const version = (await query(`SELECT current_setting('server_version_num')::int AS version,
    current_setting('fsync') AS fsync,current_setting('full_page_writes') AS full_page_writes,
    current_setting('synchronous_commit') AS synchronous_commit`)).rows[0]
  check(version.version === 170011 && version.fsync === 'on' && version.full_page_writes === 'on' && version.synchronous_commit === 'on')
  return version
}
async function closeDatabase() {
  if (!database) return true
  const client = database
  database = null
  const closed = await bounded(client.end().then(() => true).catch(() => false),3000,false)
  if (!closed) client.connection?.stream?.destroy()
  return closed
}

function owned(role, executable, argv, environment, observeWorker = false, cwd = root) {
  active()
  const entry = spawnOwned(executable, argv, environment, cwd, observeWorker)
  entry.role = role
  entry.normalStop = false
  entry.done.then(code => { entry.exitCode = code })
  children.push(entry)
  return entry
}
async function stop(entry) {
  if (!entry) return true
  const until = now() + 35000
  if (!entry.exited) {
    entry.normalStop = true
    try { process.kill(-entry.child.pid, 'SIGTERM') } catch { entry.normalStop = false }
  } else if (groupAlive(entry)) {
    entry.normalStop = false
    try { process.kill(-entry.child.pid, 'SIGTERM') } catch {}
  }
  while ((!entry.exited || groupAlive(entry)) && now() < until) await delay(25)
  if (!entry.exited || groupAlive(entry)) {
    entry.normalStop = false
    try { process.kill(-entry.child.pid, 'SIGKILL') } catch {}
    await bounded(entry.done,5000,null)
    const forcedUntil = now() + 3000
    while (groupAlive(entry) && now() < forcedUntil) await delay(25)
  }
  if (![0, 143].includes(entry.exitCode)) entry.normalStop = false
  return entry.exited && !groupAlive(entry) && entry.normalStop
}
async function ready(entry, predicate, maximum = 60000) {
  const until = Math.min(deadline, now() + maximum)
  while (now() < until) {
    active(); check(!entry.exited)
    try { if (await predicate()) return } catch {}
    await delay(100)
  }
  throw new Error('Startup readiness deadline exceeded.')
}
async function request(origin, route, options = {}) {
  active()
  return jsonRequest(origin, route, { ...options, timeout: remaining(options.timeout ?? 5000) })
}
async function textResource(route, marker) {
  const response = await fetch(`${uiOrigin}${route}`, { redirect: 'error', signal: AbortSignal.timeout(remaining(5000)) })
  check(response.status === 200)
  let length = 0, text = ''
  for await (const chunk of response.body) {
    length += chunk.length; check(length <= 1048576); text += Buffer.from(chunk).toString('utf8')
  }
  check(text.includes(marker))
}
async function probes() {
  for (const route of ['/actuator/health/liveness','/actuator/health/readiness']) {
    const result = await request(apiOrigin, route); check(result.response.status === 200 && result.value?.status === 'UP')
  }
  const proxied = await request(uiOrigin, '/api/system/status')
  check(proxied.response.status === 200 && proxied.value?.service === 'relay' && proxied.value?.role === 'api'
    && proxied.value?.phase === 7 && proxied.value?.database === 'connected')
}
async function startApplications() {
  const key = (await readFile(masterFile, 'utf8')).trim()
  const decoded = Buffer.from(key, 'base64')
  check(decoded.length === 32 && decoded.toString('base64') === key)
  if (shared) check(shared.RELAY_SIGNING_MASTER_KEY === key)
  shared = { ...commandEnvironment(), SPRING_CONFIG_LOCATION: 'classpath:/', DB_URL: `jdbc:postgresql://127.0.0.1:${settings.port}/${settings.database}`,
    DB_USERNAME: settings.user, DB_PASSWORD: settings.password, RELAY_SIGNING_MASTER_KEY: key,
    RELAY_DELIVERY_ALLOW_LOCAL_RECEIVER: 'true', RELAY_DELIVERY_LOCAL_RECEIVER_URL: receiverUrl,
    RELAY_OPERATIONS_ENABLED: 'false', SERVER_ADDRESS: '127.0.0.1', SERVER_SERVLET_SESSION_COOKIE_SECURE: 'false' }
  decoded.fill(0)
  api = owned('api', java, args, { ...shared, PORT: apiOrigin.split(':').at(-1), SPRING_PROFILES_ACTIVE: 'development' })
  await ready(api, async () => {
    const result = await request(apiOrigin, '/api/system/status', { timeout: 1000 })
    return result.response.status === 200 && result.value?.service === 'relay' && result.value?.database === 'connected'
  })
  worker = owned('worker', java, args, { ...shared, SPRING_PROFILES_ACTIVE: 'worker,development' }, true)
  await ready(worker, async () => worker.ready)
}

async function bootstrap() {
  account = { email: `startup-${randomBytes(12).toString('hex')}@example.test`, password: `Startup!${randomBytes(24).toString('base64url')}` }
  session = new Session(apiOrigin)
  await session.refreshCsrf()
  let result = await session.request('POST','/api/auth/register',account)
  check(result.response.status === 201 && UUID.test(result.value?.id ?? ''))
  await login(session)
  result = await session.request('POST','/api/workspaces',{ name: 'Isolated startup verification' })
  check(result.response.status === 201 && UUID.test(result.value?.id ?? '') && result.value.deliveryConcurrencyLimit === 4)
  const workspaceId = result.value.id
  result = await session.request('POST',`/api/workspaces/${workspaceId}/endpoints`,{ url: receiverUrl })
  check(result.response.status === 201 && UUID.test(result.value?.id ?? ''))
  endpoint = { workspaceId, endpointId: result.value.id }
  result = await session.request('POST',`/api/workspaces/${workspaceId}/api-keys`,{ name: 'Disposable startup client' })
  check(result.response.status === 201 && /^rly_[0-9a-f]{32}\.[A-Za-z0-9_-]{43}$/.test(result.value?.key ?? ''))
  apiKey = result.value.key
  endpointSecret = await revealSecret(session)
}
async function login(currentSession) {
  active()
  await currentSession.refreshCsrf()
  const result = await currentSession.request('POST','/api/auth/login',new URLSearchParams(account).toString(),'application/x-www-form-urlencoded')
  check(result.response.status === 204)
  await currentSession.refreshCsrf()
  const me = await currentSession.request('GET','/api/auth/me')
  check(me.response.status === 200 && me.value?.email === account.email)
}
async function revealSecret(currentSession) {
  active()
  const result = await currentSession.request('POST',`/api/workspaces/${endpoint.workspaceId}/endpoints/${endpoint.endpointId}/signing-secret`)
  check(result.response.status === 200 && typeof result.value?.secret === 'string')
  const bytes = Buffer.from(result.value.secret,'base64')
  check(bytes.length === 32 && bytes.toString('base64') === result.value.secret)
  return bytes
}

async function delivered(sequence) {
  healthy()
  const payload = makePayload(sequence,1024)
  const accepted = await request(uiOrigin,`/api/v1/endpoints/${endpoint.endpointId}/events`,{
    method:'POST',headers:{Authorization:`Bearer ${apiKey}`,'Content-Type':'application/json'},body:payload })
  check(accepted.response.status === 202 && UUID.test(accepted.value?.eventId ?? '') && UUID.test(accepted.value?.deliveryId ?? ''))
  const event = accepted.value, until = Math.min(deadline,now()+30000)
  while (now()<until) {
    healthy()
    const rows = (await query(`SELECT e.payload,d.status,d.claim_generation::int,d.run_attempts,d.replay_generation::int,
      a.id::text AS attempt_id,a.outcome,a.http_status,a.failure_code,a.response_preview FROM events e
      JOIN deliveries d ON d.workspace_id=e.workspace_id AND d.event_id=e.id
      LEFT JOIN delivery_attempts a ON a.workspace_id=d.workspace_id AND a.delivery_id=d.id
      WHERE e.workspace_id=$1 AND e.id=$2 AND d.id=$3`,[endpoint.workspaceId,event.eventId,event.deliveryId])).rows
    check(rows.length===1 && rows[0].payload.equals(payload) && rows[0].status!=='FAILED')
    const row=rows[0]
    if(row.status==='SUCCEEDED') {
      check(row.claim_generation===1 && row.run_attempts===1 && row.replay_generation===0 && row.outcome==='SUCCEEDED'
        && UUID.test(row.attempt_id ?? '') && row.http_status===200 && row.failure_code===null && row.response_preview==='{"duplicate":false}')
      return { ...event, attemptId: row.attempt_id }
    }
    await delay(100)
  }
  throw new Error('Startup delivery deadline exceeded.')
}
async function migrationsAndCounts(events) {
  const migrations=(await query('SELECT version,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank')).rows
  check(migrations.length===7 && migrations.every((row,index)=>row.version===String(index+1) && row.success===true))
  const counts={}
  for(const table of TABLES) counts[table]=(await query(`SELECT count(*)::int AS count FROM public.${table}`)).rows[0].count
  for(const table of TABLES) check(counts[table]=== (['events','deliveries','delivery_attempts'].includes(table)?events:1))
  return {successfulMigrations:7,accounts:counts.accounts,workspaces:counts.workspaces,endpoints:counts.endpoints,
    acceptedEvents:counts.events,successfulDeliveries:counts.deliveries,retainedAttempts:counts.delivery_attempts}
}

async function cleanup() {
  if(cleanupPromise) return cleanupPromise
  stopping=true
  cleanupDeadline=now()+120000
  cleanupPromise=(async()=>{
    const pending=[...commands]
    for(const entry of pending) killCommand(entry)
    const commandStops=await Promise.all(pending.map(async entry=>{
      await bounded(entry.done,3000,null)
      const stopped=await commandGroupStopped(entry)
      if(stopped && entry.exited)commands.delete(entry)
      return stopped && entry.exited
    }))
    cleanupState.ownedCommandsStopped=commandStops.every(Boolean)
    const stops=await Promise.allSettled(children.map(entry=>stop(entry)))
    cleanupState.ownedProcessesStopped=children.every(entry=>entry.exited && !groupAlive(entry))
    cleanupState.normalApplicationShutdown=children.filter(entry=>['api','worker'].includes(entry.role)).every(entry=>entry.normalStop)
    if(stops.some(result=>result.status==='rejected')) cleanupState.normalApplicationShutdown=false
    cleanupState.databaseClosed=await closeDatabase()
    if(ownsProject && quiescent(cleanupState)) {
      try {
        await inspectResources(true)
        await compose(['down','--volumes','--remove-orphans','--timeout','10'],60000,true)
        const absent=await inspectResources(true)
        cleanupState.composeRemoved=true
        cleanupState.containersAbsent=absent.containers.length===0
        cleanupState.volumeAbsent=absent.volume===null
      } catch {}
    }
    cleanupState.ownedCommandsStopped=cleanupState.ownedCommandsStopped && commands.size===0
    const filesSafeToRemove=quiescent(cleanupState) && (!ownsProject || (cleanupState.composeRemoved && cleanupState.containersAbsent && cleanupState.volumeAbsent))
    try { if(!filesSafeToRemove) throw new Error('Recovery configuration retained.')
    if(createdDirectory) {
      const canonicalRoot=await realpath(root), canonicalDirectory=await realpath(directory)
      check(canonicalDirectory===path.join(canonicalRoot,'work/startup',owner.id))
      for(const file of createdFiles) {
        ownedFile(root,directory,file,owner)
        try {await unlink(file)} catch(error) {if(error.code!=='ENOENT') throw error}
      }
    }
      cleanupState.temporarySecretsRemoved=true
    } catch {}
    endpointSecret?.fill(0)
    if(shared) {shared.DB_PASSWORD=null;shared.RELAY_SIGNING_MASTER_KEY=null}
    if(settings) settings.password=null
    apiKey=null;account=null
    return cleanupState
  })()
  return cleanupPromise
}

async function main() {
  check(process.argv.length===2)
  check(process.versions.node===(await readFile(path.join(root,'.node-version'),'utf8')).trim())
  java=process.env.STARTUP_JAVA_BIN ?? (process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java'):'java')
  const javaDetails=await checkJava(java,(await readFile(path.join(root,'.java-version'),'utf8')).trim())
  const jar=path.resolve(process.env.STARTUP_JAR ?? path.join(root,'backend/target/relay-0.0.1-SNAPSHOT.jar'))
  const artifact=await stat(jar);check(artifact.isFile() && artifact.size>0 && artifact.size<=536870912)
  const hash=createHash('sha256');for await(const chunk of createReadStream(jar)){active();hash.update(chunk)}
  args=['-Xms64m','-Xmx256m','-jar',jar]
  stage='docker_preflight'
  const context=JSON.parse(await command('docker',['context','inspect','default'],10000))
  check(Array.isArray(context) && context.length===1)
  dockerHost=localDockerEndpoint(context[0]?.Endpoints?.docker?.Host)
  const dockerVersion=JSON.parse(await docker(['info','--format','{{json .ServerVersion}}'],15000))
  check(typeof dockerVersion==='string' && /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(dockerVersion))
  const composeVersion=await docker(['compose','version','--short'],10000)
  check(/^v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(composeVersion) && Number(composeVersion.replace(/^v/,'').split('.')[0])>=2)
  preflightPassed=true
  stage='owned_compose_configuration'
  settings={port:await freePort(),database:`relay_startup_${owner.id}`,user:`relay_startup_${owner.id}`,password:randomBytes(32).toString('base64url')}
  composeHash=digest(await readFile(composeFile))
  await mkdir(reportDirectory,{recursive:true})
  await mkdir(directory)
  createdDirectory=true
  check(await realpath(directory)===path.join(await realpath(root),'work/startup',owner.id))
  await writeFile(envFile,`POSTGRES_DB=${settings.database}\nPOSTGRES_USER=${settings.user}\nPOSTGRES_PASSWORD=${settings.password}\nPOSTGRES_PORT=${settings.port}\n`,{flag:'wx',mode:0o600})
  createdFiles.push(envFile)
  await writeFile(masterFile,randomBytes(32).toString('base64'),{flag:'wx',mode:0o600})
  createdFiles.push(masterFile)
  const config=JSON.parse(await compose(['config','--format','json'],15000))
  verifyCompose(config,owner,settings)
  const absent=await inspectResources()
  check(absent.containers.length===0 && absent.volume===null)
  ownsProject=true;cleanupState.ownershipGranted=true
  report.environment={os:{platform:os.platform(),release:os.release(),architecture:os.arch()},node:process.versions.node,
    java:javaDetails,docker:dockerVersion,compose:composeVersion,jarSha256:hash.digest('hex'),composeFileSha256:composeHash}
  stage='first_startup'
  await compose(['up','-d','--wait','--wait-timeout','100'],120000)
  const created=await inspectResources();check(created.containers.length===1 && created.volume)
  await connectDatabase()
  check((await query("SELECT count(*)::int AS count FROM pg_tables WHERE schemaname='public'")).rows[0].count===0)
  apiOrigin=`http://127.0.0.1:${await freePort()}`
  uiOrigin=`http://127.0.0.1:${await freePort()}`
  const receiverPort=await freePort();receiverUrl=`http://127.0.0.1:${receiverPort}/webhook`
  await startApplications()
  await bootstrap()
  receiver=owned('receiver',java,[path.join(root,'samples/receiver/Receiver.java')],{
    ...commandEnvironment(),RECEIVER_SIGNING_SECRET:endpointSecret.toString('base64'),PORT:String(receiverPort)})
  await ready(receiver,async()=>(await request(`http://127.0.0.1:${receiverPort}`,'/webhook',{timeout:1000})).response.status===405,30000)
  vite=owned('vite',process.execPath,[path.join(frontend,'node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',uiOrigin.split(':').at(-1),'--strictPort'],
    {...commandEnvironment(),E2E_API_TARGET:apiOrigin},false,frontend)
  await ready(vite,async()=>(await fetch(uiOrigin,{signal:AbortSignal.timeout(remaining(1000))})).status===200)
  await textResource('/','/@vite/client');await textResource('/src/main.tsx','createRoot');await probes()
  stage='first_delivery'
  const first=await delivered(1)
  const before=await migrationsAndCounts(1)
  const me=await session.request('GET','/api/auth/me');check(me.response.status===200)
  const oldCookie=session.cookie;check(typeof oldCookie==='string' && oldCookie.length>0)
  const encrypted=(await query('SELECT encrypted_key FROM endpoint_signing_keys WHERE workspace_id=$1 AND endpoint_id=$2',[endpoint.workspaceId,endpoint.endpointId])).rows[0].encrypted_key
  report.firstStartup={result:'success',composeInitialization:true,devHtmlAndCompiledModule:true,apiProxy:true,publicProbes:true,
    authenticatedSessionBeforeStop:true,signedSampleReceiverDelivery:true,counts:before}
  stage='retained_restart'
  check((await Promise.all([stop(worker),stop(api)])).every(Boolean))
  check(await closeDatabase())
  await inspectResources()
  await compose(['down','--timeout','10'],45000)
  const retained=await inspectResources();check(retained.containers.length===0 && retained.volume)
  await compose(['up','-d','--wait','--wait-timeout','100'],120000)
  await connectDatabase()
  await startApplications()
  const expired=await request(apiOrigin,'/api/auth/me',{headers:{Cookie:`RELAY_SESSION=${oldCookie}`}})
  check(expired.response.status===401)
  const fresh=new Session(apiOrigin);await login(fresh);session=fresh
  const workspaces=await session.request('GET','/api/workspaces')
  check(workspaces.response.status===200 && Array.isArray(workspaces.value) && workspaces.value.some(row=>row.id===endpoint.workspaceId))
  const restored=await revealSecret(session)
  check(timingSafeEqual(restored,endpointSecret));restored.fill(0)
  const persisted=(await query('SELECT encrypted_key FROM endpoint_signing_keys WHERE workspace_id=$1 AND endpoint_id=$2',[endpoint.workspaceId,endpoint.endpointId])).rows[0].encrypted_key
  check(Buffer.isBuffer(encrypted) && encrypted.equals(persisted))
  check((await query("SELECT count(*)::int AS count FROM events WHERE workspace_id=$1 AND id=$2",[endpoint.workspaceId,first.eventId])).rows[0].count===1)
  const previous = (await query(`SELECT a.id::text AS attempt_id,a.outcome,a.http_status,a.failure_code,
    a.claim_generation::int,a.run_attempt,a.replay_generation::int,d.status
    FROM deliveries d JOIN delivery_attempts a ON a.workspace_id=d.workspace_id AND a.delivery_id=d.id
    WHERE d.workspace_id=$1 AND d.id=$2 AND d.event_id=$3`,[endpoint.workspaceId,first.deliveryId,first.eventId])).rows
  check(previous.length===1 && previous[0].attempt_id===first.attemptId && previous[0].outcome==='SUCCEEDED'
    && previous[0].status==='SUCCEEDED' && previous[0].http_status===200 && previous[0].failure_code===null
    && previous[0].claim_generation===1 && previous[0].run_attempt===1 && previous[0].replay_generation===0)
  await probes();await textResource('/src/main.tsx','createRoot')
  const second=await delivered(2)
  check(second.eventId!==first.eventId && second.deliveryId!==first.deliveryId)
  report.retainedRestart={result:'success',composeVolumeRetained:true,sameMasterFileReused:true,encryptedEndpointKeyRetained:true,
    existingAccountAndWorkspaceRetained:true,oldAuthenticatedSessionRejected:true,newLoginSucceeded:true,
    previousEventRetained:true,previousAttemptRetained:true,sameApiKeyAccepted:true,signedSampleReceiverDelivery:true}
  stage='final_validation'
  report.finalCounts=await migrationsAndCounts(2)
  healthy()
  report.result='success'
}

for(const signal of ['SIGINT','SIGTERM']) process.once(signal,()=>{
  stopSignal=true;stopping=true
  for(const entry of commands) killCommand(entry)
  for(const entry of children) if(!entry.exited) {try{process.kill(-entry.child.pid,'SIGTERM')}catch{}}
  setTimeout(()=>process.exit(130),125000).unref()
})

try {await main()}
catch {report.result='failure';report.failureStage=stage;process.exitCode=1}
finally {
  try {report.cleanup=await cleanup()} catch {report.cleanup=cleanupState;report.result='failure';report.failureStage='cleanup';process.exitCode=1}
  if(stopSignal || !cleanupState.ownedProcessesStopped || !cleanupState.normalApplicationShutdown || !cleanupState.ownedCommandsStopped || !cleanupState.databaseClosed
    || (ownsProject && (!cleanupState.composeRemoved || !cleanupState.containersAbsent || !cleanupState.volumeAbsent))
    || (preflightPassed && !cleanupState.temporarySecretsRemoved)) {
    report.result='failure';report.failureStage??='cleanup';process.exitCode=stopSignal?130:1
  }
  report.completedAt=new Date().toISOString()
  if(preflightPassed) {
    try {
      await mkdir(reportDirectory,{recursive:true})
      check(path.relative(await realpath(root),await realpath(reportDirectory))===path.join('work','startup'))
      await writeFile(path.join(reportDirectory,`startup-${new Date().toISOString().replace(/[:.]/g,'-')}-${randomBytes(4).toString('hex')}.json`),
        `${JSON.stringify(report,null,2)}\n`,{flag:'wx',mode:0o600})
    } catch {report.result='failure';process.exitCode=1}
  }
  const stages = ['configuration','docker_preflight','owned_compose_configuration','first_startup','first_delivery',
    'retained_restart','final_validation','cleanup']
  const safeStage = stages.includes(report.failureStage) ? report.failureStage : 'report'
  console.log(report.result==='success'?'[Startup] Compose, development startup, retained restart and cleanup passed.'
    :`[Startup] Failed at ${safeStage}; raw diagnostics and configuration are suppressed.`)
}
