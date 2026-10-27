import { spawn, execFile } from 'node:child_process'
import { createHmac, timingSafeEqual } from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'
import { makePayload } from './measurements.mjs'

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const TABLES = ['delivery_attempts', 'deliveries', 'events', 'endpoint_signing_keys', 'api_keys', 'endpoints', 'workspaces', 'accounts']
export const BATCH_TIMEOUT_MS = 180000
export const OBSERVATION_INTERVAL_MS = 100
const WORKER_READY = 'Worker ready; database connected and bounded delivery configured'

export function requireCondition(condition) { if (!condition) throw new Error('Benchmark verification failed.') }
export function now() { return Number(process.hrtime.bigint()) / 1000000 }
export function remaining(deadline, maximum = 10000) {
  const value = Math.floor(deadline - now())
  requireCondition(value > 0)
  return Math.min(value, maximum)
}
export function systemEnvironment() {
  return Object.fromEntries(Object.entries(process.env).filter(([name]) =>
    /^(PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|HOME|USERPROFILE|APPDATA|LOCALAPPDATA|HOMEDRIVE|HOMEPATH|PROCESSOR_ARCHITECTURE|NUMBER_OF_PROCESSORS)$/i.test(name)))
}

export async function checkJava(java, expected) {
  const options = { env: systemEnvironment(), windowsHide: true, timeout: 5000, maxBuffer: 8192 }
  const result = await promisify(execFile)(java, ['-version'], options)
  const quoted = result.stderr.match(/version "([0-9.]+)"/)
  const build = result.stderr.match(/(?:build |Temurin-)([0-9.]+\+[0-9]+)(?:[^0-9]|$)/)
  requireCondition(quoted?.[1] === expected && build?.[1] === `${expected}+1` && result.stderr.includes('Temurin'))
  const javac = path.join(path.dirname(java), process.platform === 'win32' ? 'javac.exe' : 'javac')
  const compiler = await promisify(execFile)(javac, ['-version'], options)
  requireCondition(`${compiler.stdout}${compiler.stderr}`.trim() === `javac ${expected}`)
  return { vendor: 'Eclipse Temurin', version: quoted[1], build: build[1], fullJdk: true }
}

export async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(error => error ? reject(error) : resolve(port))
    })
  })
}

export function spawnOwned(java, args, env, cwd, observeWorker = false) {
  const child = spawn(java, args, { cwd, env, detached: process.platform !== 'win32', windowsHide: true,
    stdio: ['ignore', observeWorker ? 'pipe' : 'ignore', 'ignore'] })
  const entry = { child, exited: false, ready: false }
  entry.done = new Promise(resolve => {
    child.once('error', () => { entry.exited = true; resolve(1) })
    child.once('exit', code => { entry.exited = true; resolve(code ?? 1) })
  })
  if (observeWorker) {
    let pending = ''
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', chunk => {
      if (entry.ready) return
      // Never retain a child log. Only a complete bounded JSON line can set the readiness bit.
      pending += chunk
      let newline
      while ((newline = pending.indexOf('\n')) >= 0) {
        const line = pending.slice(0, newline).trim()
        pending = pending.slice(newline + 1)
        if (line.length <= 8192 && line.startsWith('{')) {
          try { if (JSON.parse(line).message === WORKER_READY) entry.ready = true } catch { /* Discard. */ }
        }
      }
      if (pending.length > 8192 || entry.ready) pending = ''
    })
  }
  return entry
}

export async function stopOwned(entry) {
  if (entry.exited || !entry.child.pid) return true
  if (process.platform === 'win32') {
    await new Promise(resolve => {
      const killer = spawn('taskkill.exe', ['/PID', String(entry.child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
      const timeout = setTimeout(() => { killer.kill(); resolve() }, 5000)
      killer.once('error', () => { clearTimeout(timeout); resolve() })
      killer.once('exit', () => { clearTimeout(timeout); resolve() })
    })
  } else {
    try { process.kill(-entry.child.pid, 'SIGTERM') } catch { /* Already exited. */ }
    await Promise.race([entry.done, delay(2000)])
    if (!entry.exited) { try { process.kill(-entry.child.pid, 'SIGKILL') } catch { /* Already exited. */ } }
  }
  await Promise.race([entry.done, delay(3000)])
  return entry.exited
}

export async function ready(entry, probe) {
  const deadline = now() + 60000
  do {
    requireCondition(!entry.exited)
    try { if (await probe()) return } catch { /* Fixed, bounded readiness retries only. */ }
    await delay(100)
  } while (now() < deadline)
  throw new Error('Benchmark process did not become ready.')
}

async function responseBytes(response, maximum = 16384) {
  const chunks = []
  let length = 0
  if (!response.body) return Buffer.alloc(0)
  for await (const chunk of response.body) {
    length += chunk.length
    requireCondition(length <= maximum)
    chunks.push(chunk)
  }
  return Buffer.concat(chunks, length)
}

export async function responseJson(response) {
  const bytes = await responseBytes(response)
  return bytes.length ? JSON.parse(bytes.toString('utf8')) : null
}

export async function jsonRequest(origin, route, { method = 'GET', headers = {}, body, timeout = 10000 } = {}) {
  const response = await fetch(`${origin}${route}`, { method, headers, body, redirect: 'error',
    signal: AbortSignal.timeout(timeout) })
  return { response, value: await responseJson(response) }
}

export class Session {
  constructor(origin) { this.origin = origin; this.cookie = null; this.csrf = null }
  async request(method, route, body, type = 'application/json') {
    const headers = {}
    if (this.cookie) headers.Cookie = `RELAY_SESSION=${this.cookie}`
    if (method !== 'GET' && this.csrf) headers['X-CSRF-TOKEN'] = this.csrf
    if (body !== undefined) headers['Content-Type'] = type
    const result = await jsonRequest(this.origin, route, { method, headers,
      body: body === undefined ? undefined : type === 'application/json' ? JSON.stringify(body) : body })
    for (const cookie of result.response.headers.getSetCookie()) {
      const match = cookie.match(/^RELAY_SESSION=([^;]*)/)
      if (match) {
        requireCondition(match[1] === '' || /^[A-Za-z0-9._~-]{1,128}$/.test(match[1]))
        this.cookie = match[1] || null
      }
    }
    return result
  }
  async refreshCsrf() {
    const result = await this.request('GET', '/api/auth/csrf')
    requireCondition(result.response.status === 200 && result.value?.headerName === 'X-CSRF-TOKEN'
      && typeof result.value.token === 'string' && result.value.token.length < 1024)
    this.csrf = result.value.token
  }
}

export async function startReceiver(settings) {
  const socketStates = new WeakMap()
  const errorCodes = ['ECONNRESET', 'HPE_INVALID_EOF_STATE', 'HPE_HEADER_OVERFLOW', 'HPE_INVALID_CONSTANT', 'ERR_HTTP_REQUEST_TIMEOUT', 'OTHER']
  const state = { secret: null, sequences: new Set(), receipts: new Map(), counters: {
    requests: 0, signaturesVerified: 0, invalidSignatures: 0, invalidHeaders: 0, payloadMismatches: 0,
    duplicates: 0, responses204: 0, requestFailures: 0, postResponseResets: 0, connectionFailures: 0, peakInFlight: 0,
    ...Object.fromEntries(errorCodes.map(code => [`socketError_${code}`, 0])) }, inFlight: 0 }
  const maximumReceipts = settings.warmup + settings.events * settings.iterations
  const server = http.createServer({ maxHeaderSize: 16384 }, async (request, response) => {
    const socket = request.socket
    let connection = socketStates.get(socket)
    if (!connection) { connection = { active: new Set(), lastCompleted: null, errorHandled: false }; socketStates.set(socket, connection) }
    const requestState = { bodyComplete: false, finished: false, failed: false, receipt: null, settled: false }
    connection.active.add(requestState)
    let timeout
    const settle = () => {
      if (!requestState.settled) { requestState.settled = true; state.inFlight-- }
      clearTimeout(timeout)
    }
    const failRequest = () => {
      if (!requestState.failed) { requestState.failed = true; state.counters.requestFailures++ }
    }
    response.once('finish', () => {
      requestState.finished = true
      connection.active.delete(requestState)
      connection.lastCompleted = { bodyComplete: requestState.bodyComplete && request.complete,
        status: response.statusCode, writableFinished: response.writableFinished, bytesRead: socket.bytesRead }
      if (response.statusCode === 204 && requestState.receipt) {
        requestState.receipt.responses++; state.counters.responses204++
      }
      settle()
    })
    response.once('close', () => {
      if (!requestState.finished) failRequest()
      connection.active.delete(requestState)
      settle()
    })
    response.once('error', failRequest)
    state.counters.requests++
    state.inFlight++
    state.counters.peakInFlight = Math.max(state.counters.peakInFlight, state.inFlight)
    timeout = setTimeout(() => { failRequest(); request.destroy(); response.destroy() }, 5000)
    try {
      if (request.method !== 'POST' || request.url !== '/receiver' || state.inFlight > 32) {
        state.counters.invalidHeaders++; response.writeHead(400, { Connection: 'close' }).end(); return
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
      const encoding = header('content-encoding')
      if (request.rawHeaders.length > 128 || !UUID.test(eventId ?? '') || !UUID.test(deliveryId ?? '') || !UUID.test(attemptId ?? '')
          || !/^[1-9][0-9]{0,18}$/.test(generation ?? '') || BigInt(generation) > 9223372036854775807n
          || !/^(0|[1-9][0-9]{0,18})$/.test(timestamp ?? '') || !/^v1=[0-9a-f]{64}$/.test(signature ?? '')
          || !/^application\/json(?:;\s*charset=(?:utf-8|"utf-8"))?$/i.test(header('content-type') ?? '')
          || (request.headers['content-encoding'] !== undefined && encoding?.toLowerCase() !== 'identity')
          || request.headers.authorization !== undefined || request.headers.cookie !== undefined) {
        state.counters.invalidHeaders++; response.writeHead(400, { Connection: 'close' }).end(); return
      }
      const chunks = []; let bytes = 0
      for await (const chunk of request) {
        bytes += chunk.length
        requireCondition(bytes <= 262144)
        chunks.push(chunk)
      }
      requestState.bodyComplete = request.complete
      const body = Buffer.concat(chunks, bytes)
      const current = BigInt(Math.floor(Date.now() / 1000)), seconds = BigInt(timestamp)
      const prefix = `relay.webhook.v1\n${timestamp}\n${eventId}\n${deliveryId}\n${attemptId}\n${generation}\n`
      const expected = state.secret && createHmac('sha256', state.secret).update(prefix, 'utf8').update(body).digest()
      if (!expected || seconds < current - 300n || seconds > current + 300n
          || !timingSafeEqual(expected, Buffer.from(signature.slice(3), 'hex'))) {
        state.counters.invalidSignatures++; response.writeHead(401).end(); return
      }
      state.counters.signaturesVerified++
      const sequence = JSON.parse(body.toString('utf8')).sequence
      if (!Number.isSafeInteger(sequence) || sequence < 1 || !state.sequences.has(sequence)
          || !body.equals(makePayload(sequence, settings.payloadBytes))) {
        state.counters.payloadMismatches++; response.writeHead(409).end(); return
      }
      let receipt = state.receipts.get(eventId)
      if (receipt) {
        state.counters.duplicates++
        requireCondition(receipt.sequence === sequence && receipt.deliveryId === deliveryId)
      } else {
        requireCondition(state.receipts.size < maximumReceipts)
        receipt = { sequence, deliveryId, requests: 0, responses: 0, attempts: new Set(), generations: new Set() }
        state.receipts.set(eventId, receipt)
      }
      receipt.requests++; receipt.attempts.add(attemptId); receipt.generations.add(generation)
      requestState.receipt = receipt
      if (settings.receiverDelayMs) await delay(settings.receiverDelayMs)
      response.writeHead(204).end()
    } catch {
      failRequest()
      if (!response.headersSent) response.writeHead(400, { Connection: 'close' })
      response.end()
    }
  })
  server.requestTimeout = 5000; server.headersTimeout = 5000; server.keepAliveTimeout = 1000; server.maxConnections = 32
  server.on('clientError', (error, socket) => {
    const connection = socketStates.get(socket)
    if (connection?.errorHandled) { socket.destroy(); return }
    if (connection) connection.errorHandled = true
    const code = errorCodes.includes(error.code) ? error.code : 'OTHER'
    state.counters[`socketError_${code}`]++
    const completed = connection?.lastCompleted
    // HttpCore IMMEDIATE teardown uses SO_LINGER(0). It can reset an idle connection after
    // the full request and our finished204. New/partial requests and all other codes still fail.
    const idleCompletedReset = code === 'ECONNRESET' && connection?.active.size === 0
      && completed?.bodyComplete && completed.status === 204 && completed.writableFinished
      && socket.bytesRead === completed.bytesRead
    if (idleCompletedReset) state.counters.postResponseResets++
    else {
      state.counters.connectionFailures++
      if (connection?.active.size) {
        for (const current of connection.active) {
          if (!current.failed) { current.failed = true; state.counters.requestFailures++ }
        }
      } else state.counters.requestFailures++
    }
    // Never append an HTTP400 after an already-finished response or retain a raw parser packet.
    socket.destroy()
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  return { server, state, url: `http://127.0.0.1:${server.address().port}/receiver` }
}
