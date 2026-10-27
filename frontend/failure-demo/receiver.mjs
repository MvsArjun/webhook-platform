import http from 'node:http'
import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import { makePayload } from '../bench/measurements.mjs'
import { UUID } from '../bench/support.mjs'

const SOCKET_CODES = ['ECONNRESET', 'HPE_INVALID_EOF_STATE', 'HPE_HEADER_OVERFLOW', 'HPE_INVALID_CONSTANT', 'ERR_HTTP_REQUEST_TIMEOUT', 'OTHER']

/** Independent wire verifier and deliberately memory-only effect deduplication for two scripted events. */
export async function startFailureReceiver() {
  const sockets = new WeakMap(), events = new Map()
  const state = { secret: null, receipts: [], effects: new Map(), inFlight: 0, closed: false,
    crashArmed: false, crashReleased: false, counters: {
      requests: 0, signaturesVerified: 0, invalidSignatures: 0, invalidHeaders: 0,
      payloadMismatches: 0, unexpectedRequests: 0, responses503: 0, responses204: 0,
      effectApplications: 0, duplicateEffectsPrevented: 0, intentionalCrashDisconnects: 0,
      requestFailures: 0, connectionFailures: 0, postResponseResets: 0, droppedConnections: 0,
      peakInFlight: 0, serverErrors: 0,
      ...Object.fromEntries(SOCKET_CODES.map(code => [`socketError_${code}`, 0])),
    } }
  let releaseGate, closePromise
  const crashGate = new Promise(resolve => { releaseGate = resolve })
  const releaseCrash = () => { state.crashReleased = true; releaseGate() }
  const armCrashDisconnect = () => {
    const receipt = state.receipts.find(item => item.sequence === 2 && item.generation === '1')
    if (!receipt || receipt.responseFinished || receipt.intentionalCrashDisconnect || state.crashReleased) {
      throw new Error('Failure demonstration receiver cannot arm crash.')
    }
    state.crashArmed = true
  }

  const server = http.createServer({ maxHeaderSize: 16384, connectionsCheckingInterval: 100 }, async (request, response) => {
    const socket = request.socket
    let connection = sockets.get(socket)
    if (!connection) { connection = { active: new Set(), completed: null, errorHandled: false }; sockets.set(socket, connection) }
    const current = { bodyComplete: false, finished: false, failed: false, settled: false,
      receipt: null, holdingCrash: false, requestBytes: null }
    connection.active.add(current)
    let timeout
    const fail = () => { if (!current.failed) { current.failed = true; state.counters.requestFailures++ } }
    const settle = () => {
      clearTimeout(timeout)
      if (!current.settled) { current.settled = true; state.inFlight-- }
    }
    const intentionalDisconnect = () => {
      if (current.receipt?.intentionalCrashDisconnect) return true
      if (!state.crashArmed || !current.holdingCrash || !current.bodyComplete || connection.active.size !== 1
          || socket.bytesRead !== current.requestBytes || current.receipt?.sequence !== 2 || current.receipt.generation !== '1') return false
      current.receipt.intentionalCrashDisconnect = true
      connection.intentionalCrashBytes = current.requestBytes
      state.counters.intentionalCrashDisconnects++
      return true
    }
    current.fail = fail
    current.intentionalDisconnect = intentionalDisconnect
    response.once('finish', () => {
      current.finished = true
      connection.active.delete(current)
      connection.completed = { bodyComplete: current.bodyComplete && request.complete,
        status: response.statusCode, writableFinished: response.writableFinished, bytesRead: socket.bytesRead }
      if (current.receipt) {
        current.receipt.responseStatus = response.statusCode
        current.receipt.responseFinished = true
        if (response.statusCode === 503) state.counters.responses503++
        else if (response.statusCode === 204) state.counters.responses204++
      }
      settle()
    })
    response.once('close', () => {
      if (!current.finished && !intentionalDisconnect()) fail()
      connection.active.delete(current)
      settle()
    })
    response.once('error', () => { if (!intentionalDisconnect()) fail() })
    state.counters.requests++
    state.inFlight++
    state.counters.peakInFlight = Math.max(state.counters.peakInFlight, state.inFlight)
    timeout = setTimeout(() => { fail(); request.destroy(); response.destroy() }, 5000)
    const reject = (counter, status) => {
      state.counters[counter]++
      response.writeHead(status, { Connection: 'close' }).end()
    }
    try {
      if (request.method !== 'POST' || request.url !== '/receiver' || state.inFlight > 4 || state.closed) {
        reject('invalidHeaders', 400); return
      }
      const header = name => {
        const values = []
        for (let index = 0; index < request.rawHeaders.length; index += 2) {
          if (request.rawHeaders[index].toLowerCase() === name) values.push(request.rawHeaders[index + 1])
        }
        return values.length === 1 && values[0].length <= 8192 ? values[0] : null
      }
      const eventId = header('x-relay-event-id'), deliveryId = header('x-relay-delivery-id')
      const attemptId = header('x-relay-attempt-id'), generation = header('x-relay-attempt')
      const timestamp = header('x-relay-timestamp'), signature = header('x-relay-signature')
      if (request.rawHeaders.length > 128 || !UUID.test(eventId ?? '') || !UUID.test(deliveryId ?? '') || !UUID.test(attemptId ?? '')
          || !/^[1-9][0-9]{0,18}$/.test(generation ?? '') || BigInt(generation) > 9223372036854775807n
          || !/^(0|[1-9][0-9]{0,18})$/.test(timestamp ?? '') || !/^v1=[0-9a-f]{64}$/.test(signature ?? '')
          || !/^application\/json(?:;\s*charset=(?:utf-8|"utf-8"))?$/i.test(header('content-type') ?? '')
          || (request.headers['content-encoding'] !== undefined && header('content-encoding')?.toLowerCase() !== 'identity')
          || request.headers.authorization !== undefined || request.headers.cookie !== undefined) {
        reject('invalidHeaders', 400); return
      }
      const chunks = []; let size = 0
      for await (const chunk of request) {
        size += chunk.length
        if (size > 16384) { reject('payloadMismatches', 413); return }
        chunks.push(chunk)
      }
      current.bodyComplete = request.complete
      current.requestBytes = socket.bytesRead
      const body = Buffer.concat(chunks, size), seconds = BigInt(timestamp)
      const nowSeconds = BigInt(Math.floor(Date.now() / 1000))
      const prefix = `relay.webhook.v1\n${timestamp}\n${eventId}\n${deliveryId}\n${attemptId}\n${generation}\n`
      const expected = state.secret?.length === 32 && createHmac('sha256', state.secret).update(prefix, 'utf8').update(body).digest()
      if (!expected || seconds < nowSeconds - 300n || seconds > nowSeconds + 300n
          || !timingSafeEqual(expected, Buffer.from(signature.slice(3), 'hex'))) {
        reject('invalidSignatures', 401); return
      }
      state.counters.signaturesVerified++
      let sequence
      try { sequence = JSON.parse(body.toString('utf8')).sequence } catch { reject('payloadMismatches', 409); return }
      if (![1, 2].includes(sequence) || !body.equals(makePayload(sequence, 1024))) { reject('payloadMismatches', 409); return }
      let event = events.get(sequence)
      const count = event?.requests ?? 0
      if (count >= 2 || generation !== String(count + 1) || state.receipts.length >= 8
          || state.receipts.some(receipt => receipt.attemptId === attemptId)
          || (event && (event.eventId !== eventId || event.deliveryId !== deliveryId))
          || (!event && [...events.values()].some(other => other.eventId === eventId || other.deliveryId === deliveryId))
          || (sequence === 2 && count === 1 && (!state.crashReleased || !state.receipts.find(receipt =>
            receipt.sequence === 2 && receipt.generation === '1')?.intentionalCrashDisconnect))) {
        reject('unexpectedRequests', 409); return
      }
      if (!event) { event = { eventId, deliveryId, requests: 0 }; events.set(sequence, event) }
      event.requests++
      const receipt = { sequence, eventId, deliveryId, attemptId, generation, timestamp,
        responseStatus: null, responseFinished: false, effectApplied: false,
        duplicateEffectPrevented: false, intentionalCrashDisconnect: false }
      state.receipts.push(receipt)
      current.receipt = receipt
      if (sequence === 1 && count === 0) { response.writeHead(503).end(); return }
      const digest = createHash('sha256').update(body).digest('hex')
      if (state.effects.has(eventId)) {
        if (state.effects.get(eventId) !== digest) { reject('payloadMismatches', 409); return }
        receipt.duplicateEffectPrevented = true
        state.counters.duplicateEffectsPrevented++
      } else {
        state.effects.set(eventId, digest)
        receipt.effectApplied = true
        state.counters.effectApplications++
      }
      if (sequence === 2 && count === 0) {
        current.holdingCrash = true
        clearTimeout(timeout)
        timeout = setTimeout(() => { fail(); request.destroy(); response.destroy(); releaseGate() }, 30000)
        await crashGate
        if (response.destroyed || current.settled) return
      }
      response.writeHead(204).end()
    } catch {
      if (!intentionalDisconnect()) fail()
      if (!response.destroyed && !response.writableEnded) {
        if (!response.headersSent) response.writeHead(400, { Connection: 'close' })
        response.end()
      }
    }
  })
  server.maxConnections = 8
  server.requestTimeout = 5000
  server.headersTimeout = 5000
  server.keepAliveTimeout = 1000
  server.on('connection', socket => sockets.set(socket, { active: new Set(), completed: null, errorHandled: false }))
  server.on('drop', () => { state.counters.droppedConnections++ })
  server.on('error', () => { state.counters.serverErrors++ })
  server.on('clientError', (error, socket) => {
    const connection = sockets.get(socket)
    if (connection?.errorHandled) { socket.destroy(); return }
    if (connection) connection.errorHandled = true
    const code = SOCKET_CODES.includes(error.code) ? error.code : 'OTHER'
    state.counters[`socketError_${code}`]++
    const completed = connection?.completed
    const idleCompletedReset = code === 'ECONNRESET' && connection?.active.size === 0
      && completed?.bodyComplete && [503, 204].includes(completed.status) && completed.writableFinished
      && socket.bytesRead === completed.bytesRead
    const intentionalCrash = code === 'ECONNRESET' && connection?.active.size === 1
      && [...connection.active][0].intentionalDisconnect()
    const closedCrashReset = code === 'ECONNRESET' && connection?.active.size === 0
      && connection.intentionalCrashBytes !== undefined && socket.bytesRead === connection.intentionalCrashBytes
    if (idleCompletedReset) state.counters.postResponseResets++
    else if (!intentionalCrash && !closedCrashReset) {
      state.counters.connectionFailures++
      if (connection?.active.size) for (const active of connection.active) active.fail()
      else state.counters.requestFailures++
    }
    socket.destroy()
  })
  await new Promise((resolve, reject) => {
    const failed = error => reject(error)
    server.once('error', failed)
    server.listen(0, '127.0.0.1', () => { server.off('error', failed); resolve() })
  })
  const close = () => {
    if (closePromise) return closePromise
    state.closed = true
    releaseCrash()
    closePromise = (async () => {
      server.closeAllConnections()
      let timeout
      const closed = await Promise.race([
        new Promise(resolve => server.close(error => resolve(!error || error.code === 'ERR_SERVER_NOT_RUNNING'))),
        new Promise(resolve => { timeout = setTimeout(() => resolve(false), 2000) }),
      ])
      clearTimeout(timeout)
      state.secret?.fill(0)
      return closed
    })()
    return closePromise
  }
  return { server, state, url: `http://127.0.0.1:${server.address().port}/receiver`, armCrashDisconnect, releaseCrash, close }
}
