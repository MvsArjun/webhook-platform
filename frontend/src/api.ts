import type { Account } from './types'

type Options = {
  method?: string; body?: unknown; form?: URLSearchParams; signal?: AbortSignal; allowUnauthorized?: boolean
}
let csrf: { headerName: string; token: string } | null = null
let authVersion = 0

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message?: string) {
    super(message ?? ({
      400: 'Some information is invalid. Check your entries and try again.',
      401: 'Your session has ended. Sign in again.',
      403: 'The security check expired. Try the action again to refresh it.',
      404: 'This item is unavailable or you do not have access to it.',
      409: 'This conflicts with existing data or the item has changed. Refresh and try again.',
      413: 'The request is too large.',
      429: 'The workspace has reached its limit. Try again after capacity is available.',
    } as Record<number, string>)[status] ?? 'The server could not complete the request. Please try again.')
    this.name = 'ApiError'
    this.status = status
  }
}

export function message(error: unknown): string {
  return error instanceof Error ? error.message : 'The request could not be completed. Please try again.'
}

async function fetchJson<T>(path: string, options: Options = {}): Promise<T> {
  const requestVersion = authVersion
  const controller = new AbortController()
  let timedOut = false
  const abort = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  const timeout = window.setTimeout(() => { timedOut = true; controller.abort() }, 15_000)
  try {
    const method = options.method ?? 'GET'
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (method !== 'GET' && csrf) headers[csrf.headerName] = csrf.token
    if (options.form) headers['Content-Type'] = 'application/x-www-form-urlencoded'
    else if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, {
      method, headers, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
      body: options.form ? options.form.toString() : options.body === undefined ? undefined : JSON.stringify(options.body),
    })
    if (!response.ok) {
      if (response.status === 403 && requestVersion === authVersion && !options.signal?.aborted) csrf = null
      if (response.status === 401 && !options.allowUnauthorized && requestVersion === authVersion && !options.signal?.aborted) {
        api.reset()
        window.dispatchEvent(new Event('relay:session-expired'))
      }
      throw new ApiError(response.status)
    }
    if (response.status === 204) return undefined as T
    return await response.json() as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (options.signal?.aborted) throw error
    throw new Error(timedOut ? 'The request timed out. Check your connection and try again.'
      : 'Cannot connect to Relay. Check that the API is running, then try again.')
  } finally {
    window.clearTimeout(timeout)
    options.signal?.removeEventListener('abort', abort)
  }
}

export const api = {
  get<T>(path: string, signal?: AbortSignal) { return fetchJson<T>(path, { signal }) },
  async write<T>(path: string, method: string, body?: unknown): Promise<T> {
    const version = authVersion
    if (!csrf) await api.refreshCsrf()
    if (version !== authVersion) throw new Error('Your session changed. Try the action again.')
    return fetchJson<T>(path, { method, body })
  },
  async refreshCsrf(signal?: AbortSignal) {
    const version = authVersion
    const token = await fetchJson<{ headerName: string; token: string }>('/api/auth/csrf', { signal, allowUnauthorized: true })
    if (version === authVersion) csrf = token
  },
  reset() { csrf = null; authVersion++ },
  me(signal?: AbortSignal) { return fetchJson<Account>('/api/auth/me', { signal, allowUnauthorized: true }) },
  async register(email: string, password: string) {
    if (!csrf) await api.refreshCsrf()
    return fetchJson<Account>('/api/auth/register', { method: 'POST', body: { email, password }, allowUnauthorized: true })
  },
  async login(email: string, password: string) {
    if (!csrf) await api.refreshCsrf()
    await fetchJson<void>('/api/auth/login', { method: 'POST', form: new URLSearchParams({ email, password }), allowUnauthorized: true })
    api.reset()
    await api.refreshCsrf()
    return api.me()
  },
  async logout() {
    await api.write<void>('/api/auth/logout', 'POST')
    api.reset()
    // Clear private UI immediately after confirmation; the next authentication write obtains a new token.
  },
}
