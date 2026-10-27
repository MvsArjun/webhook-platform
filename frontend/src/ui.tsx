import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'

export function Brand() {
  return <span className="brand"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M8 9h10a7 7 0 0 1 0 14H8" stroke="currentColor" strokeWidth="3" /><path d="m14 4-6 5 6 5M18 18l6 5-6 5" stroke="currentColor" strokeWidth="3" strokeLinejoin="round" /></svg>relay<span>.</span></span>
}
export function Status({ value }: { value: string }) {
  return <span className={`status status-${value.toLowerCase()}`}><span />{value.replaceAll('_', ' ')}</span>
}
export function ErrorNotice({ error, retry }: { error: string | null; retry?: () => void }) {
  if (!error) return null
  return <div className="error-notice" role="alert" data-testid="error-state"><span>{error}</span>{retry && <button onClick={retry}>Try again</button>}</div>
}
export function Loading({ text = 'Loading…' }: { text?: string }) {
  return <div className="loading-state" role="status" data-testid="loading-state"><span className="spinner" />{text}</div>
}
export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <div className="empty-state" data-testid="empty-state"><span className="empty-symbol" aria-hidden="true">↗</span><h3>{title}</h3><p>{children}</p></div>
}
export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const id = useId()
  useEffect(() => { dialog.current?.showModal(); return () => dialog.current?.close() }, [])
  return <dialog ref={dialog} aria-labelledby={id} onCancel={event => { event.preventDefault(); onClose() }}>
    <div className="modal-heading"><h2 id={id}>{title}</h2><button className="icon-button" onClick={onClose} aria-label="Close dialog">×</button></div>{children}
  </dialog>
}
export function Pagination({ offset, limit, count, hasMore, onChange }: { offset: number; limit: number; count: number; hasMore: boolean; onChange: (offset: number) => void }) {
  return <div className="pagination"><span>{count ? `${offset + 1}–${offset + count}` : '0'} shown</span><div><button disabled={offset === 0} onClick={() => onChange(Math.max(0, offset - limit))}>Previous page</button><button disabled={!hasMore} onClick={() => onChange(offset + limit)}>Next page</button></div></div>
}
export function date(value: string | null) {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
export function shortId(value: string) { return value.slice(0, 8) }
