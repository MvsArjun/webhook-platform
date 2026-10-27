import test from 'node:test'
import assert from 'node:assert/strict'
import { createHmac, randomUUID } from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
import { makePayload } from '../bench/measurements.mjs'
import { startFailureReceiver } from './receiver.mjs'

async function fixture(t) {
  const receiver = await startFailureReceiver()
  receiver.state.secret = Buffer.alloc(32, 19)
  t.after(async () => { assert.equal(await receiver.close(), true) })
  return receiver
}

function signed(receiver, body, overrides = {}) {
  const values = { eventId: randomUUID(), deliveryId: randomUUID(), attemptId: randomUUID(), generation: '1',
    timestamp: String(Math.floor(Date.now() / 1000)), ...overrides }
  const prefix = `relay.webhook.v1\n${values.timestamp}\n${values.eventId}\n${values.deliveryId}\n${values.attemptId}\n${values.generation}\n`
  const digest = createHmac('sha256', receiver.state.secret).update(prefix, 'utf8').update(body).digest('hex')
  return { values, headers: { 'Content-Type': 'application/json', 'X-Relay-Event-Id': values.eventId,
    'X-Relay-Delivery-Id': values.deliveryId, 'X-Relay-Attempt-Id': values.attemptId, 'X-Relay-Attempt': values.generation,
    'X-Relay-Timestamp': values.timestamp, 'X-Relay-Signature': `v1=${digest}` } }
}

async function post(receiver, body, headers) {
  const response = await fetch(receiver.url, { method: 'POST', body, headers, signal: AbortSignal.timeout(5000) })
  await response.arrayBuffer()
  return response.status
}

async function until(predicate) {
  const deadline = Date.now() + 2000
  while (!predicate() && Date.now() < deadline) await delay(5)
  assert.ok(predicate(), 'Expected bounded receiver transition.')
}

test('503 then204 verifies distinct signed attempts and applies one receiver effect', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(1, 1024)
  const first = signed(receiver, body)
  assert.equal(await post(receiver, body, first.headers), 503)
  assert.equal(receiver.state.effects.size, 0)
  const next = signed(receiver, body, { ...first.values, attemptId: randomUUID(), generation: '2' })
  assert.equal(await post(receiver, body, next.headers), 204)
  assert.deepEqual(receiver.state.receipts.map(receipt => receipt.generation), ['1', '2'])
  assert.deepEqual(receiver.state.receipts.map(receipt => receipt.responseStatus), [503, 204])
  assert.deepEqual(receiver.state.receipts.map(receipt => receipt.effectApplied), [false, true])
  assert.equal(receiver.state.effects.size, 1)
  assert.equal(receiver.state.counters.effectApplications, 1)
  assert.equal(receiver.state.counters.signaturesVerified, 2)
  assert.equal(receiver.state.counters.requestFailures, 0)
})

test('processed held request disconnects only when armed, then retry suppresses its repeated effect', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(2, 1024)
  const first = signed(receiver, body)
  const client = http.request(receiver.url, { method: 'POST', headers: first.headers })
  client.on('error', () => {})
  t.after(() => client.destroy())
  client.end(body)
  await until(() => receiver.state.receipts.length === 1)
  assert.equal(receiver.state.receipts[0].effectApplied, true)
  assert.equal(receiver.state.receipts[0].responseFinished, false)
  receiver.armCrashDisconnect()
  client.destroy()
  await until(() => receiver.state.inFlight === 0)
  receiver.releaseCrash()
  const next = signed(receiver, body, { ...first.values, attemptId: randomUUID(), generation: '2' })
  assert.equal(await post(receiver, body, next.headers), 204)
  assert.equal(receiver.state.receipts[0].responseStatus, null)
  assert.equal(receiver.state.receipts[0].intentionalCrashDisconnect, true)
  assert.equal(receiver.state.receipts[1].duplicateEffectPrevented, true)
  assert.equal(receiver.state.receipts[1].effectApplied, false)
  assert.equal(receiver.state.effects.size, 1)
  assert.equal(receiver.state.counters.intentionalCrashDisconnects, 1)
  assert.equal(receiver.state.counters.duplicateEffectsPrevented, 1)
  assert.equal(receiver.state.counters.effectApplications, 1)
  assert.equal(receiver.state.counters.requestFailures, 0)
  assert.equal(receiver.state.counters.connectionFailures, 0)
})

test('an unarmed held-request disconnect remains a failure', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(2, 1024)
  const client = http.request(receiver.url, { method: 'POST', headers: signed(receiver, body).headers })
  client.on('error', () => {})
  t.after(() => client.destroy())
  client.end(body)
  await until(() => receiver.state.receipts.length === 1)
  client.destroy()
  await until(() => receiver.state.inFlight === 0)
  receiver.releaseCrash()
  assert.equal(receiver.state.counters.intentionalCrashDisconnects, 0)
  assert.equal(receiver.state.counters.requestFailures, 1)
  assert.equal(receiver.state.receipts[0].responseFinished, false)
})

test('a late reset after the armed held-response close is attributed to that exact crash socket', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(2, 1024)
  const requested = once(receiver.server, 'request')
  const client = http.request(receiver.url, { method: 'POST', headers: signed(receiver, body).headers })
  client.on('error', () => {})
  t.after(() => client.destroy())
  client.end(body)
  const [request, response] = await requested
  await until(() => receiver.state.receipts.length === 1)
  receiver.armCrashDisconnect()
  // Exercise the permitted event order explicitly before physically closing the socket.
  response.emit('close')
  assert.equal(receiver.state.inFlight, 0)
  receiver.server.emit('clientError', Object.assign(new Error('Synthetic socket event'), { code: 'ECONNRESET' }), request.socket)
  await delay(5)
  receiver.releaseCrash()
  assert.equal(receiver.state.counters.intentionalCrashDisconnects, 1)
  assert.equal(receiver.state.counters.requestFailures, 0)
  assert.equal(receiver.state.counters.connectionFailures, 0)
})

test('tampered/stale signatures, extra credentials and changed exact bytes cannot apply effects', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(1, 1024)
  const valid = signed(receiver, body)
  const changed = Buffer.from(body)
  changed[changed.indexOf('x')] = 'y'.charCodeAt(0)
  assert.equal(await post(receiver, changed, valid.headers), 401)
  const stale = signed(receiver, body, { timestamp: String(Math.floor(Date.now() / 1000) - 301) })
  assert.equal(await post(receiver, body, stale.headers), 401)
  assert.equal(await post(receiver, body, { ...valid.headers, Authorization: 'synthetic-extra-credential' }), 400)
  assert.equal(await post(receiver, changed, signed(receiver, changed).headers), 409)
  assert.equal(receiver.state.receipts.length, 0)
  assert.equal(receiver.state.effects.size, 0)
  assert.equal(receiver.state.counters.invalidSignatures, 2)
  assert.equal(receiver.state.counters.invalidHeaders, 1)
  assert.equal(receiver.state.counters.payloadMismatches, 1)
})

test('repeated signing headers are rejected and conflicting event identities cannot replace receipts', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(1, 1024)
  const first = signed(receiver, body)
  const raw = Object.entries(first.headers).flat()
  raw.push('X-Relay-Event-Id', first.values.eventId, 'Content-Length', String(body.length), 'Host', new URL(receiver.url).host)
  const status = await new Promise((resolve, reject) => {
    const client = http.request(receiver.url, { method: 'POST', headers: raw }, response => {
      response.resume(); response.once('end', () => resolve(response.statusCode))
    })
    client.setTimeout(5000, () => client.destroy())
    client.once('error', reject)
    client.end(body)
  })
  assert.equal(status, 400)
  assert.equal(await post(receiver, body, first.headers), 503)
  const conflicting = signed(receiver, body, { generation: '2' })
  assert.equal(await post(receiver, body, conflicting.headers), 409)
  assert.equal(receiver.state.receipts.length, 1)
  assert.equal(receiver.state.effects.size, 0)
  assert.equal(receiver.state.counters.invalidHeaders, 1)
  assert.equal(receiver.state.counters.unexpectedRequests, 1)
})

test('finished503 and204 resets are teardown while an incomplete body reset is a real failure', { timeout: 10000 }, async t => {
  const receiver = await fixture(t), body = makePayload(1, 1024)
  const first = signed(receiver, body)
  const connection = once(receiver.server, 'connection')
  assert.equal(await post(receiver, body, first.headers), 503)
  const [socket] = await connection
  receiver.server.emit('clientError', Object.assign(new Error('Synthetic socket event'), { code: 'ECONNRESET' }), socket)
  const nextConnection = once(receiver.server, 'connection')
  const next = signed(receiver, body, { ...first.values, attemptId: randomUUID(), generation: '2' })
  assert.equal(await post(receiver, body, next.headers), 204)
  const [nextSocket] = await nextConnection
  receiver.server.emit('clientError', Object.assign(new Error('Synthetic socket event'), { code: 'ECONNRESET' }), nextSocket)
  assert.equal(receiver.state.counters.postResponseResets, 2)
  assert.equal(receiver.state.counters.requestFailures, 0)
  const address = new URL(receiver.url), headers = signed(receiver, makePayload(2, 1024)).headers
  const requested = once(receiver.server, 'request')
  const client = net.connect({ host: address.hostname, port: Number(address.port) })
  client.on('error', () => {})
  t.after(() => client.destroy())
  await once(client, 'connect')
  const lines = ['POST /receiver HTTP/1.1', `Host: ${address.host}`, `Content-Length: ${body.length}`,
    ...Object.entries(headers).map(([name, value]) => `${name}: ${value}`), '', '']
  client.write(Buffer.concat([Buffer.from(lines.join('\r\n')), body.subarray(0, 10)]))
  const [request] = await requested
  receiver.server.emit('clientError', Object.assign(new Error('Synthetic socket event'), { code: 'ECONNRESET' }), request.socket)
  await until(() => receiver.state.inFlight === 0)
  assert.equal(receiver.state.counters.postResponseResets, 2)
  assert.equal(receiver.state.counters.requestFailures, 1)
  assert.equal(receiver.state.counters.connectionFailures, 1)
  assert.equal(receiver.state.receipts.length, 2)
})

test('close is idempotent and zeroes the supplied signing-key buffer', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const key = receiver.state.secret
  assert.equal(await receiver.close(), true)
  assert.equal(await receiver.close(), true)
  assert.equal(receiver.state.closed, true)
  assert.ok(key.every(byte => byte === 0))
  assert.equal(receiver.server.listening, false)
})
