import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSettings } from './config.mjs'

const environment = () => ({ BENCH_DB_URL: 'jdbc:postgresql://127.0.0.1:55432/relay_bench',
  BENCH_DB_USERNAME: 'benchmark-only', BENCH_DB_PASSWORD: 'private-configuration-marker',
  BENCH_DB_ALLOW_DESTRUCTIVE: 'true' })

function rejected(argv, env = environment()) {
  assert.throws(() => parseSettings(argv, env), error => {
    assert.equal(error.message, 'Invalid benchmark configuration.')
    assert.equal(error.cause, undefined)
    assert.equal(error.stack.includes('private-configuration-marker'), false)
    return true
  })
}

test('default workload is explicit and bounded, and preserves a separately supplied database credential', () => {
  const settings = parseSettings([], environment())
  assert.equal(settings.events, 1000)
  assert.equal(settings.iterations, 3)
  assert.equal(settings.concurrency, 8)
  assert.equal(settings.payloadBytes, 1024)
  assert.equal(settings.warmup, 100)
  assert.equal(settings.receiverDelayMs, 10)
  assert.equal(settings.database.host, '127.0.0.1')
  assert.equal(settings.database.port, 55432)
  assert.equal(settings.database.database, 'relay_bench')
  assert.equal(settings.database.password, environment().BENCH_DB_PASSWORD)
})

test('valid numeric boundaries and literal loopback variants retain their requested settings', () => {
  const maximum = parseSettings(['--events=5000', '--iterations=5', '--concurrency=32', '--payload-bytes=16384',
    '--warmup=500', '--receiver-delay-ms=1000'], environment())
  assert.deepEqual([maximum.events, maximum.iterations, maximum.concurrency, maximum.payloadBytes, maximum.warmup,
    maximum.receiverDelayMs], [5000, 5, 32, 16384, 500, 1000])
  const minimum = parseSettings(['--events=100', '--iterations=1', '--concurrency=1', '--payload-bytes=256',
    '--warmup=0', '--receiver-delay-ms=0'], environment())
  assert.deepEqual([minimum.events, minimum.iterations, minimum.concurrency, minimum.payloadBytes, minimum.warmup,
    minimum.receiverDelayMs], [100, 1, 1, 256, 0, 0])
  for (const host of ['localhost', '[::1]']) {
    const settings = parseSettings([], { ...environment(), BENCH_DB_URL: `jdbc:postgresql://${host}:5432/relay_bench_test` })
    assert.equal(settings.database.host, host.replace(/[\[\]]/g, ''))
  }
})

test('unknown, duplicate, ambiguous and out-of-bounds command-line arguments fail with safe diagnostics', () => {
  for (const argv of [['--unknown=1'], ['--events=100', '--events=200'], ['--events', '100'], ['--events=99'],
    ['--events=5001'], ['--iterations=0'], ['--iterations=6'], ['--concurrency=0'], ['--concurrency=33'],
    ['--payload-bytes=255'], ['--payload-bytes=16385'], ['--warmup=501'], ['--receiver-delay-ms=1001'],
    ['--events=100.0'], ['--events=1e3'], ['--events=100junk'], ['--events=+100'], ['--events= 100'],
    ['--events=10000000000000000000000000000000'], ['--events=private-configuration-marker'], ['--events=-100']]) {
    rejected(argv)
  }
})

test('cleanup consent and all database settings are mandatory and never inferred', () => {
  for (const missing of ['BENCH_DB_ALLOW_DESTRUCTIVE', 'BENCH_DB_URL', 'BENCH_DB_USERNAME', 'BENCH_DB_PASSWORD']) {
    const env = environment()
    delete env[missing]
    rejected([], env)
  }
  for (const consent of ['', 'false', 'TRUE', '1']) rejected([], { ...environment(), BENCH_DB_ALLOW_DESTRUCTIVE: consent })
  for (const name of ['BENCH_DB_USERNAME', 'BENCH_DB_PASSWORD']) {
    rejected([], { ...environment(), [name]: ' ' })
    rejected([], { ...environment(), [name]: 'x'.repeat(4097) })
  }
})

test('only an unambiguous dedicated loopback database with an explicit canonical port is accepted', () => {
  const urls = ['jdbc:postgresql://127.0.0.1/relay_bench', 'jdbc:postgresql://127.0.0.1:0/relay_bench',
    'jdbc:postgresql://127.0.0.1:65536/relay_bench', 'jdbc:postgresql://127.0.0.1:05432/relay_bench',
    'jdbc:postgresql://127.0.0.1:5432/relay', 'jdbc:postgresql://127.0.0.1:5432/relay_test',
    'jdbc:postgresql://127.0.0.1:5432/relay_bench?sslmode=disable', 'jdbc:postgresql://127.0.0.1:5432/relay_bench#x',
    'jdbc:postgresql://private-configuration-marker@127.0.0.1:5432/relay_bench',
    'jdbc:postgresql://example.test:5432/relay_bench', 'jdbc:postgresql://192.168.1.2:5432/relay_bench',
    'jdbc:postgresql://127.1:5432/relay_bench', 'jdbc:postgresql://2130706433:5432/relay_bench',
    'jdbc:postgresql://[::ffff:127.0.0.1]:5432/relay_bench', 'jdbc:postgresql://127.0.0.1:5432/relay%5Fbench',
    'jdbc:postgresql://127.0.0.1:5432/x/../relay_bench', 'jdbc:postgresql://127.0.0.1:5432//relay_bench',
    'jdbc:postgresql://127.0.0.1:5432/relay_bench/', ' jdbc:postgresql://127.0.0.1:5432/relay_bench']
  for (const jdbc of urls) rejected([], { ...environment(), BENCH_DB_URL: jdbc })
})
