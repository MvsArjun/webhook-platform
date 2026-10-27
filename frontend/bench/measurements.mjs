/** Linear-interpolated sample percentiles in the same units as the input. */
export function distribution(values) {
  if (!Array.isArray(values)) {
    throw new Error('Invalid nonnegative measurement samples.')
  }
  for (const value of values) {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) throw new Error('Invalid nonnegative measurement samples.')
  }
  if (values.length === 0) return { samples: 0, min: null, mean: null, p50: null, p95: null, p99: null, max: null }
  const sorted = [...values].sort((left, right) => left - right)
  function percentile(fraction) {
    const index = (sorted.length - 1) * fraction
    const lower = Math.floor(index)
    const upper = Math.ceil(index)
    return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower)
  }
  // Dividing first avoids overflowing a finite sample sum.
  const mean = sorted.reduce((total, value) => total + value / sorted.length, 0)
  return { samples: sorted.length, min: sorted[0], mean, p50: percentile(0.5), p95: percentile(0.95),
    p99: percentile(0.99), max: sorted.at(-1) }
}

/** Build distinct, exact-size UTF-8 JSON bodies without relying on character counts. */
export function makePayload(sequence, totalBytes) {
  if (!Number.isSafeInteger(sequence) || sequence < 0 || !Number.isSafeInteger(totalBytes) || totalBytes < 1 || totalBytes > 16384) {
    throw new Error('Invalid bounded payload parameters.')
  }
  const empty = JSON.stringify({ sequence, data: '' })
  const padding = totalBytes - Buffer.byteLength(empty, 'utf8')
  if (padding < 0) throw new Error('Payload size cannot contain its metadata.')
  return Buffer.from(JSON.stringify({ sequence, data: 'x'.repeat(padding) }), 'utf8')
}
