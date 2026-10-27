import test from 'node:test'
import assert from 'node:assert/strict'
import { createHmac, randomUUID } from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
import { startReceiver } from './support.mjs'
import { makePayload } from './measurements.mjs'

async function fixture(t) {
  const receiver = await startReceiver({ warmup: 0, events: 100, iterations: 1, payloadBytes: 256, receiverDelayMs: 0 })
  receiver.state.secret = Buffer.alloc(32, 7)
  receiver.state.sequences.add(1)
  t.after(async () => {
    receiver.server.closeAllConnections()
    await new Promise(resolve => receiver.server.close(resolve))
    receiver.state.secret.fill(0)
  })
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
  const result = await fetch(receiver.url, { method: 'POST', headers, body, signal: AbortSignal.timeout(5000) })
  await result.arrayBuffer()
  return result.status
}

test('the benchmark receiver verifies the wire signature and counts repeated stable event IDs', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const body = makePayload(1, 256)
  const first = signed(receiver, body)
  assert.equal(await post(receiver, body, first.headers), 204)
  const repeated = signed(receiver, body, { ...first.values, attemptId: randomUUID(), generation: '2' })
  assert.equal(await post(receiver, body, repeated.headers), 204)
  assert.equal(receiver.state.receipts.size, 1)
  assert.equal(receiver.state.receipts.get(first.values.eventId).attempts.size, 2)
  assert.deepEqual([...receiver.state.receipts.get(first.values.eventId).generations].sort(), ['1', '2'])
  assert.equal(receiver.state.counters.signaturesVerified, 2)
  assert.equal(receiver.state.counters.duplicates, 1)
  assert.equal(receiver.state.counters.responses204, 2)
})

test('tampered and stale requests cannot contribute a verified receipt', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const body = makePayload(1, 256)
  const headers = signed(receiver, body).headers
  const changed = Buffer.from(body)
  changed[changed.indexOf('x')] = 'y'.charCodeAt(0)
  assert.equal(await post(receiver, changed, headers), 401)
  const stale = signed(receiver, body, { timestamp: String(Math.floor(Date.now() / 1000) - 301) })
  assert.equal(await post(receiver, body, stale.headers), 401)
  assert.equal(receiver.state.receipts.size, 0)
  assert.equal(receiver.state.counters.signaturesVerified, 0)
  assert.equal(receiver.state.counters.invalidSignatures, 2)
})

test('valid signatures with unexpected bytes or leaked customer headers fail the healthy workload check', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const unexpected = Buffer.from(JSON.stringify({ sequence: 1, data: 'different-body' }))
  assert.equal(await post(receiver, unexpected, signed(receiver, unexpected).headers), 409)
  const body = makePayload(1, 256)
  assert.equal(await post(receiver, body, { ...signed(receiver, body).headers, Authorization: 'private-header-marker' }), 400)
  assert.equal(receiver.state.receipts.size, 0)
  assert.equal(receiver.state.counters.payloadMismatches, 1)
  assert.equal(receiver.state.counters.invalidHeaders, 1)
})

test('repeated signature metadata headers are rejected before receipt accounting', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const body = makePayload(1, 256)
  const headers = signed(receiver, body).headers
  const raw = Object.entries(headers).flat()
  raw.push('X-Relay-Event-Id', headers['X-Relay-Event-Id'], 'Content-Length', String(body.length),
    'Host', new URL(receiver.url).host)
  const status = await new Promise((resolve, reject) => {
    const request = http.request(receiver.url, { method: 'POST', headers: raw }, response => {
      response.resume()
      response.once('end', () => resolve(response.statusCode))
    })
    request.setTimeout(5000, () => request.destroy())
    request.once('error', reject)
    request.end(body)
  })
  assert.equal(status, 400)
  assert.equal(receiver.state.receipts.size, 0)
  assert.equal(receiver.state.counters.invalidHeaders, 1)
  assert.equal(receiver.state.counters.requestFailures, 0)
})

test('a reset after a fully finished signed response is counted as teardown, not failed delivery', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const connection = once(receiver.server, 'connection')
  const body = makePayload(1, 256)
  assert.equal(await post(receiver, body, signed(receiver, body).headers), 204)
  const [socket] = await connection
  receiver.server.emit('clientError', Object.assign(new Error('Synthetic socket event'), { code: 'ECONNRESET' }), socket)
  assert.equal(receiver.state.counters.responses204, 1)
  assert.equal(receiver.state.counters.postResponseResets, 1)
  assert.equal(receiver.state.counters.requestFailures, 0)
})

test('a reset with an incomplete request body remains a real failure and creates no receipt', { timeout: 10000 }, async t => {
  const receiver = await fixture(t)
  const address = new URL(receiver.url)
  const body = makePayload(1, 256)
  const headers = signed(receiver, body).headers
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
  const deadline = Date.now() + 1000
  while (receiver.state.inFlight && Date.now() < deadline) await delay(5)
  assert.equal(receiver.state.inFlight, 0)
  assert.ok(receiver.state.counters.requestFailures >= 1)
  assert.equal(receiver.state.counters.postResponseResets, 0)
  assert.equal(receiver.state.receipts.size, 0)
})
