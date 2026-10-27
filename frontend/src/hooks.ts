import { useEffect, useRef, useState } from 'react'
import { message } from './api'

export function useResource<T>(key: string, fetcher: (signal: AbortSignal) => Promise<T>) {
  const loader = useRef(fetcher)
  loader.current = fetcher
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState<{ data: T | null; loading: boolean; error: string | null }>({ data: null, loading: true, error: null })
  useEffect(() => {
    const controller = new AbortController()
    setState({ data: null, loading: true, error: null })
    void loader.current(controller.signal).then(data => {
      if (!controller.signal.aborted) setState({ data, loading: false, error: null })
    }).catch(error => {
      if (!controller.signal.aborted) setState({ data: null, loading: false, error: message(error) })
    })
    return () => controller.abort()
  }, [key, revision])
  return { ...state, refresh: () => setRevision(value => value + 1) }
}

export function useMutation() {
  const mounted = useRef(true)
  const busy = useRef(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  async function run<T>(task: () => Promise<T>, success?: (value: T) => void) {
    if (busy.current) return
    busy.current = true
    setPending(true)
    setError(null)
    try {
      const value = await task()
      if (mounted.current) success?.(value)
    } catch (failure) {
      if (mounted.current) setError(message(failure))
    } finally {
      busy.current = false
      if (mounted.current) setPending(false)
    }
  }
  return { pending, error, run, clearError: () => setError(null) }
}
