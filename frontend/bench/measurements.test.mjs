import test from 'node:test'
import assert from 'node:assert/strict'
import { distribution, makePayload } from './measurements.mjs'

test('sample percentiles interpolate observations without mutating input', () => {
  const observations = [100, 0, 20, 10]
  const result = distribution(observations)
  assert.deepEqual(observations, [100, 0, 20, 10])
  assert.equal(result.samples, 4)
  assert.equal(result.min, 0)
  assert.equal(result.mean, 32.5)
  assert.equal(result.p50, 15)
  assert.ok(Math.abs(result.p95 - 88) < 1e-10)
  assert.ok(Math.abs(result.p99 - 97.6) < 1e-10)
  assert.equal(result.max, 100)
})

test('empty and single-observation distributions distinguish missing measurements from zero', () => {
  assert.deepEqual(distribution([]), { samples: 0, min: null, mean: null, p50: null, p95: null, p99: null, max: null })
  assert.deepEqual(distribution([0]), { samples: 1, min: 0, mean: 0, p50: 0, p95: 0, p99: 0, max: 0 })
  assert.deepEqual(distribution([7.25]), { samples: 1, min: 7.25, mean: 7.25, p50: 7.25, p95: 7.25, p99: 7.25, max: 7.25 })
  assert.equal(distribution([Number.MAX_VALUE, Number.MAX_VALUE]).mean, Number.MAX_VALUE)
})

test('invalid samples are rejected rather than silently coercing or dropping observations', () => {
  for (const samples of [null, {}, [null], ['1'], [NaN], [Infinity], [-0.001], new Array(1)]) {
    assert.throws(() => distribution(samples), { message: 'Invalid nonnegative measurement samples.' })
  }
})

test('payload size stays exact across changing sequence widths and stays valid UTF-8 JSON', () => {
  for (const size of [256, 1024, 16384]) {
    for (const sequence of [0, 9, 10, 9999, Number.MAX_SAFE_INTEGER]) {
      const payload = makePayload(sequence, size)
      assert.equal(payload.length, size)
      assert.equal(Buffer.byteLength(payload.toString('utf8')), size)
      const decoded = JSON.parse(payload)
      assert.equal(decoded.sequence, sequence)
      assert.match(decoded.data, /^x*$/)
    }
  }
  assert.notDeepEqual(makePayload(1, 1024), makePayload(2, 1024))
})

test('invalid or oversized payload requests cannot allocate an unbounded body', () => {
  for (const [sequence, size] of [[-1, 256], [0.5, 256], [Number.MAX_SAFE_INTEGER + 1, 256], [1, 16385], [1, Infinity], [1, 256.5]]) {
    assert.throws(() => makePayload(sequence, size), { message: 'Invalid bounded payload parameters.' })
  }
  assert.throws(() => makePayload(1, 1), { message: 'Payload size cannot contain its metadata.' })
})
