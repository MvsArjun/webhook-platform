const OPTIONS = Object.freeze({
  events: { field: 'events', fallback: 1000, minimum: 100, maximum: 5000 },
  iterations: { field: 'iterations', fallback: 3, minimum: 1, maximum: 5 },
  concurrency: { field: 'concurrency', fallback: 8, minimum: 1, maximum: 32 },
  'payload-bytes': { field: 'payloadBytes', fallback: 1024, minimum: 256, maximum: 16384 },
  warmup: { field: 'warmup', fallback: 100, minimum: 0, maximum: 500 },
  'receiver-delay-ms': { field: 'receiverDelayMs', fallback: 10, minimum: 0, maximum: 1000 },
})

/** No normalization, diagnostics containing supplied values, or implicit database selection. */
export function parseSettings(argv = [], env = process.env) {
  try {
    if (!Array.isArray(argv) || !env || env.BENCH_DB_ALLOW_DESTRUCTIVE !== 'true') throw new Error()
    const match = typeof env.BENCH_DB_URL === 'string' && env.BENCH_DB_URL.match(
      /^jdbc:postgresql:\/\/(127\.0\.0\.1|localhost|\[::1\]):([1-9][0-9]{0,4})\/([A-Za-z0-9_]+_bench(?:_test)?)$/,
    )
    if (!match || match[0] !== env.BENCH_DB_URL || Number(match[2]) > 65535) throw new Error()
    for (const name of ['BENCH_DB_USERNAME', 'BENCH_DB_PASSWORD']) {
      if (typeof env[name] !== 'string' || !env[name].trim() || env[name].length > 4096) throw new Error()
    }
    const settings = Object.fromEntries(Object.values(OPTIONS).map(option => [option.field, option.fallback]))
    const seen = new Set()
    for (const argument of argv) {
      const option = typeof argument === 'string' && argument.match(/^--([a-z-]+)=(0|[1-9][0-9]*)$/)
      if (!option || option[0] !== argument || !Object.hasOwn(OPTIONS, option[1]) || seen.has(option[1])) throw new Error()
      seen.add(option[1])
      const rule = OPTIONS[option[1]]
      const value = Number(option[2])
      if (!Number.isSafeInteger(value) || value < rule.minimum || value > rule.maximum) throw new Error()
      settings[rule.field] = value
    }
    return { database: { jdbc: env.BENCH_DB_URL, host: match[1].replace(/^\[|\]$/g, ''), port: Number(match[2]),
      database: match[3], user: env.BENCH_DB_USERNAME, password: env.BENCH_DB_PASSWORD }, ...settings }
  } catch {
    throw new Error('Invalid benchmark configuration.')
  }
}
