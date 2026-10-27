import { randomBytes } from 'node:crypto'
import { mkdir, realpath, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { makePayload } from '../bench/measurements.mjs'
import { UUID, TABLES, jsonRequest, now, requireCondition as check } from '../bench/support.mjs'
import { DEMO_SETTINGS, FailureRuntime } from './runtime.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const runtime = new FailureRuntime(root)
const report = { schemaVersion: 1, result: 'failure', startedAt: new Date().toISOString(),
  settings: DEMO_SETTINGS, environment: null, destinationRecovery: null, workerRecovery: null, cleanup: null }
let stage = 'configuration', stopSignal = false

async function submit(sequence) {
  runtime.healthy()
  const owner = runtime.owner
  const result = await jsonRequest(runtime.origin, `/api/v1/endpoints/${owner.endpointId}/events`, {
    method: 'POST', headers: { Authorization: `Bearer ${owner.apiKey}`, 'Content-Type': 'application/json' },
    body: makePayload(sequence, 1024), timeout: 10000,
  })
  check(result.response.status === 202 && UUID.test(result.value?.eventId ?? '') && UUID.test(result.value?.deliveryId ?? ''))
  return { eventId: result.value.eventId, deliveryId: result.value.deliveryId, sequence }
}

async function snapshot(event) {
  const row = (await runtime.query(`SELECT e.id::text AS event_id,e.endpoint_id::text AS endpoint_id,e.payload,
    d.id::text AS delivery_id,d.status,d.claim_generation::int,d.run_attempts,d.max_attempts,d.replay_generation::int,
    d.next_attempt_at IS NOT NULL AS scheduled,d.lease_expires_at::text AS lease_expires_at,
    COALESCE(d.lease_expires_at>clock_timestamp(),false) AS lease_unexpired
    FROM events e JOIN deliveries d ON d.workspace_id=e.workspace_id AND d.event_id=e.id
    WHERE e.workspace_id=$1 AND e.id=$2`, [runtime.owner.workspaceId, event.eventId])).rows
  check(row.length === 1)
  const delivery = row[0]
  check(delivery.event_id === event.eventId && delivery.delivery_id === event.deliveryId
    && delivery.endpoint_id === runtime.owner.endpointId && delivery.payload.equals(makePayload(event.sequence, 1024))
    && delivery.max_attempts === 5 && delivery.replay_generation === 0)
  const attempts = (await runtime.query(`SELECT id::text AS id,claim_generation::int,replay_generation::int,run_attempt,
    outcome,http_status,failure_code,duration_ms,finished_at IS NOT NULL AS finished,
    response_preview IS NULL AS no_preview,response_truncated
    FROM delivery_attempts WHERE workspace_id=$1 AND delivery_id=$2 ORDER BY claim_generation`,
  [runtime.owner.workspaceId, event.deliveryId])).rows
  check(attempts.length <= 2 && delivery.status !== 'FAILED')
  return { delivery, attempts }
}

async function until(worker, predicate, deadline) {
  do {
    check(now() < deadline)
    runtime.healthy(worker)
    const result = await predicate()
    if (result) { check(now() < deadline); return result }
    await delay(50)
  } while (now() < deadline)
  throw new Error('Demonstration deadline exceeded.')
}

function history(attempts) {
  return attempts.map(row => ({ claimGeneration: row.claim_generation, replayGeneration: row.replay_generation,
    runAttempt: row.run_attempt, outcome: row.outcome, httpStatus: row.http_status, failureCode: row.failure_code,
    durationMs: row.duration_ms === null ? null : Number(row.duration_ms), finished: row.finished }))
}

function verifyFinal(event, final, expectedFirst) {
  const { delivery, attempts } = final
  check(delivery.status === 'SUCCEEDED' && delivery.claim_generation === 2 && delivery.run_attempts === 2
    && !delivery.scheduled && attempts.length === 2 && attempts[0].id !== attempts[1].id)
  for (let index = 0; index < 2; index++) {
    const row = attempts[index]
    check(UUID.test(row.id) && row.claim_generation === index + 1 && row.run_attempt === index + 1
      && row.replay_generation === 0 && row.finished)
  }
  const [first, second] = attempts
  check(first.outcome === expectedFirst && second.outcome === 'SUCCEEDED' && second.http_status === 204
    && second.failure_code === null && second.duration_ms !== null && Number(second.duration_ms) >= 0)
  if (expectedFirst === 'FAILED') check(first.http_status === 503 && first.failure_code === 'HTTP_STATUS'
    && first.duration_ms !== null && Number(first.duration_ms) >= 0)
  else check(first.http_status === null && first.failure_code === null && first.duration_ms === null
    && first.no_preview && !first.response_truncated)
  const receipts = runtime.receiver.state.receipts.filter(row => row.sequence === event.sequence)
  check(receipts.length === 2 && receipts.every((row, index) => row.eventId === event.eventId
    && row.deliveryId === event.deliveryId && row.attemptId === attempts[index].id && row.generation === String(index + 1)))
  return receipts
}

async function destinationRecovery(worker) {
  const started = now(), event = await submit(1), deadline = started + DEMO_SETTINGS.scenarioDeadlineMs
  let retryWaitObserved = false
  const final = await until(worker, async () => {
    const current = await snapshot(event)
    if (current.delivery.status === 'RETRY_WAIT' && current.delivery.scheduled
      && current.attempts.length === 1 && current.attempts[0].outcome === 'FAILED'
      && current.attempts[0].http_status === 503) retryWaitObserved = true
    return current.delivery.status === 'SUCCEEDED' && current.attempts.length === 2 ? current : null
  }, deadline)
  const receipts = verifyFinal(event, final, 'FAILED')
  check(receipts[0].responseStatus === 503 && receipts[0].responseFinished && !receipts[0].effectApplied
    && receipts[1].responseStatus === 204 && receipts[1].responseFinished && receipts[1].effectApplied)
  report.destinationRecovery = { result: 'success', elapsedMs: now() - started, acceptedEvents: 1,
    retryWaitObserved, stableEventDeliveryPayload: true, distinctAttemptIds: true, replayUsed: false,
    history: history(final.attempts), receiver: { verifiedRequests: 2, responseStatuses: [503, 204], effectApplications: 1 } }
}

async function workerRecovery(worker) {
  const started = now(), event = await submit(2), deadline = started + DEMO_SETTINGS.scenarioDeadlineMs
  const receipt = await until(worker, async () => runtime.receiver.state.receipts.find(row => row.sequence === 2), deadline)
  check(receipt.effectApplied && !receipt.responseFinished && receipt.generation === '1')
  const before = await snapshot(event)
  check(before.delivery.status === 'IN_FLIGHT' && before.delivery.lease_unexpired && before.attempts.length === 1
    && before.attempts[0].outcome === 'IN_PROGRESS' && before.attempts[0].id === receipt.attemptId)
  runtime.receiver.armCrashDisconnect()
  const crashStarted = now()
  await runtime.forceCrash(worker)
  const forcedExitMs = now() - crashStarted
  const after = await snapshot(event)
  check(worker.exited && after.delivery.status === 'IN_FLIGHT' && after.delivery.lease_unexpired
    && after.attempts.length === 1 && after.attempts[0].outcome === 'IN_PROGRESS'
    && after.attempts[0].id === receipt.attemptId && !after.attempts[0].finished)
  await until(null, async () => receipt.intentionalCrashDisconnect, deadline)
  check(!receipt.responseFinished)
  runtime.receiver.releaseCrash()
  const replacement = await runtime.startWorker(deadline)
  const final = await until(replacement, async () => {
    const current = await snapshot(event)
    return current.delivery.status === 'SUCCEEDED' && current.attempts.length === 2 ? current : null
  }, deadline)
  const receipts = verifyFinal(event, final, 'UNKNOWN')
  check(receipts[0].intentionalCrashDisconnect && !receipts[0].responseFinished && receipts[0].effectApplied
    && receipts[1].responseStatus === 204 && receipts[1].responseFinished && !receipts[1].effectApplied
    && receipts[1].duplicateEffectPrevented)
  const expiry = (await runtime.query(`SELECT finished_at >= $3::timestamptz AS expired
    FROM delivery_attempts WHERE workspace_id=$1 AND id=$2`,
  [runtime.owner.workspaceId, before.attempts[0].id, before.delivery.lease_expires_at])).rows
  check(expiry.length === 1 && expiry[0].expired === true)
  report.workerRecovery = { result: 'success', elapsedMs: now() - started, forcedExitMs,
    crashMethod: process.platform === 'win32' ? 'taskkill /T /F on owned worker' : 'SIGKILL on owned worker process group',
    receiverEffectBeforeKill: true, responseWithheld: true, workerExitConfirmed: true,
    inFlightAndInProgressAfterKill: true, leaseUnexpiredAfterKill: true, naturalExpiryBeforeUnknown: true,
    stableEventDeliveryPayload: true, distinctAttemptIds: true, replayUsed: false,
    history: history(final.attempts), receiver: { verifiedRequests: 2, completedResponses204: 1,
      intentionalCrashDisconnects: 1, effectApplications: 1, duplicateEffectsPrevented: 1 } }
  return replacement
}

async function finalValidation() {
  const counts = {}
  for (const table of TABLES) counts[table] = (await runtime.query(`SELECT count(*)::int AS count FROM public.${table}`)).rows[0].count
  for (const table of TABLES) check(counts[table] === (table === 'delivery_attempts' ? 4 : ['events','deliveries'].includes(table) ? 2 : 1))
  const { state } = runtime.receiver
  const counters = state.counters
  check(state.receipts.length === 4 && state.effects.size === 2 && state.inFlight === 0
    && counters.requests === 4 && counters.signaturesVerified === 4 && counters.responses503 === 1 && counters.responses204 === 2
    && counters.effectApplications === 2 && counters.duplicateEffectsPrevented === 1 && counters.intentionalCrashDisconnects === 1)
  for (const key of ['invalidSignatures','invalidHeaders','payloadMismatches','unexpectedRequests','requestFailures',
    'connectionFailures','droppedConnections','serverErrors']) check(counters[key] === 0)
  report.finalCounts = { acceptedEvents: 2, durableEvents: counts.events, durableDeliveries: counts.deliveries,
    retainedAttempts: counts.delivery_attempts, verifiedReceiverRequests: 4, simulatedEffects: 2,
    duplicateEffectsPrevented: 1, receiverCounters: { ...counters } }
}

async function saveReport() {
  const directory = path.join(root, 'work/failure-demo')
  await mkdir(directory, { recursive: true })
  const relative = path.relative(await realpath(root), await realpath(directory))
  check(relative && !relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative))
  const name = `failures-${new Date().toISOString().replace(/[:.]/g, '-')}-${randomBytes(4).toString('hex')}.json`
  await writeFile(path.join(directory, name), `${JSON.stringify(report, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
}

for (const signal of ['SIGINT','SIGTERM']) process.once(signal, () => {
  stopSignal = true
  void runtime.cleanup().catch(() => {})
  setTimeout(() => process.exit(130), 45000).unref()
})

try {
  check(process.argv.length === 2)
  await runtime.initialize(value => { stage = value })
  report.environment = runtime.environment
  stage = 'worker_startup'
  const worker = await runtime.startWorker()
  stage = 'destination_recovery'
  await destinationRecovery(worker)
  stage = 'worker_recovery'
  const replacement = await workerRecovery(worker)
  stage = 'final_validation'
  runtime.healthy(replacement)
  await finalValidation()
  report.result = 'success'
} catch {
  report.result = 'failure'
  report.failureStage = stage
  process.exitCode = 1
} finally {
  try { report.cleanup = await runtime.cleanup() }
  catch { report.cleanup = runtime.cleanupResult; report.result = 'failure'; report.failureStage = 'cleanup'; process.exitCode = 1 }
  if (!report.cleanup.processesStopped || !report.cleanup.receiverClosed || !report.cleanup.databaseClosed
    || (runtime.ownsDatabase && !report.cleanup.fixturesRemoved) || stopSignal) {
    report.result = 'failure'; report.failureStage ??= 'cleanup'; process.exitCode = stopSignal ? 130 : 1
  }
  report.completedAt = new Date().toISOString()
  try {
    await saveReport()
    console.log(report.result === 'success' ? '[Failure demo] Both scenarios passed; safe report written under work/failure-demo.'
      : '[Failure demo] Failed; safe report written under work/failure-demo. Raw errors and child output are suppressed.')
  } catch { console.error('[Failure demo] Report could not be written; raw diagnostics are suppressed.'); process.exitCode = 1 }
}
