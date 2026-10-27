import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api } from './api'
import { useMutation, useResource } from './hooks'
import { Empty, ErrorNotice, Loading, Modal, Pagination, Status, date, shortId } from './ui'
import type { ApiKey, Attempt, CreatedApiKey, Endpoint, EventView, Page, ReplayAcceptance, Workspace } from './types'

const PAGE_SIZE = 10
const base = (workspace: Workspace) => `/api/workspaces/${workspace.id}`
export const routeHref = (workspace: string, view: string, event?: string) => `#/workspaces/${encodeURIComponent(workspace)}/${view}${event ? `/${encodeURIComponent(event)}` : ''}`

function Secret({ kind, value, onClose }: { kind: 'API key' | 'signing secret'; value: string; onClose: () => void }) {
  return <section className="secret-panel" role="region" aria-label={kind === 'API key' ? 'API key created' : 'Endpoint signing secret'}><div className="secret-heading"><h2>{kind === 'API key' ? 'API key created' : 'Endpoint signing secret'}</h2><button onClick={onClose}>Dismiss {kind}</button></div>
    <p className="muted">{kind === 'API key' ? 'This key is shown only once. Copy it before dismissing this window.' : 'Use this secret to verify signed deliveries. You can reveal it again here.'}</p>
    <label>{kind}<textarea className="secret-value" aria-label={kind} data-testid={kind === 'API key' ? 'api-key-secret' : 'signing-secret'} readOnly value={value} rows={3} spellCheck={false} autoComplete="off" /></label>
    <p className="field-help">Treat this value like a password. Keep it out of public code and logs.</p>
  </section>
}

export function EndpointsView({ workspace }: { workspace: Workspace }) {
  const [offset, setOffset] = useState(0)
  const [create, setCreate] = useState(false)
  const [url, setUrl] = useState('')
  const [secret, setSecret] = useState<string | null>(null)
  const endpoints = useResource<Page<Endpoint>>(`${workspace.id}:${offset}`, signal => api.get(`${base(workspace)}/endpoints?limit=${PAGE_SIZE}&offset=${offset}`, signal))
  const mutation = useMutation()
  function submit(event: FormEvent) {
    event.preventDefault()
    void mutation.run(() => api.write<Endpoint>(`${base(workspace)}/endpoints`, 'POST', { url: url.trim() }), () => {
      setCreate(false); setUrl(''); setOffset(0); endpoints.refresh()
    })
  }
  return <section><div className="page-heading"><div><div className="eyebrow">DESTINATIONS</div><h1>Endpoints</h1><p>Where your events go. Pause a destination whenever you need to.</p></div><button className="primary" onClick={() => { mutation.clearError(); setCreate(true) }}>+ Register endpoint</button></div>
    {secret !== null && <Secret kind="signing secret" value={secret} onClose={() => setSecret(null)} />}
    <ErrorNotice error={!create ? mutation.error : null} />
    <div className="surface"><div className="surface-heading"><div><h2>Registered destinations</h2><p>HTTPS endpoints receive signed JSON deliveries.</p></div><button onClick={endpoints.refresh} disabled={endpoints.loading}>Refresh endpoints</button></div>
      {endpoints.loading ? <Loading text="Loading endpoints…" /> : endpoints.error ? <ErrorNotice error={endpoints.error} retry={endpoints.refresh} /> : endpoints.data && <>
        {endpoints.data.items.length === 0 ? <Empty title="No endpoints on this page">Register a destination to start accepting events for delivery.</Empty> : <div className="table-scroll"><table data-testid="endpoints-table"><thead><tr><th>Destination</th><th>Status</th><th>Registered</th><th>Actions</th></tr></thead><tbody>{endpoints.data.items.map(endpoint => <tr key={endpoint.id} data-testid={`endpoint-${endpoint.id}`}><td><span className="endpoint-url">{endpoint.url}</span><code className="sub-id">{endpoint.id}</code></td><td><Status value={endpoint.paused ? 'PAUSED' : 'ACTIVE'} /></td><td className="nowrap">{date(endpoint.createdAt)}</td><td><div className="row-actions"><button disabled={mutation.pending} onClick={() => void mutation.run(() => api.write(`${base(workspace)}/endpoints/${endpoint.id}`, 'PATCH', { paused: !endpoint.paused }), endpoints.refresh)}>{endpoint.paused ? 'Resume endpoint' : 'Pause endpoint'}</button><button className="text-button" disabled={mutation.pending} onClick={() => void mutation.run(() => api.write<{ secret: string }>(`${base(workspace)}/endpoints/${endpoint.id}/signing-secret`, 'POST'), result => setSecret(result.secret))}>Reveal signing secret</button></div></td></tr>)}</tbody></table></div>}
        <Pagination offset={offset} limit={PAGE_SIZE} count={endpoints.data.items.length} hasMore={endpoints.data.hasMore} onChange={setOffset} />
      </>}
    </div>
    <div className="context-note"><span aria-hidden="true">↗</span><p>Pausing prevents new delivery claims. Requests already in flight may still finish.</p></div>
    {create && <Modal title="Register endpoint" onClose={() => { if (!mutation.pending) setCreate(false) }}><form onSubmit={submit}><label>Endpoint URL<input type="url" required maxLength={2048} autoFocus placeholder="https://example.com/webhooks" value={url} onChange={event => setUrl(event.target.value)} /></label><p className="field-help">Use HTTPS. A configured development receiver may use local HTTP.</p><ErrorNotice error={mutation.error} /><div className="modal-actions"><button type="button" disabled={mutation.pending} onClick={() => setCreate(false)}>Cancel</button><button className="primary" disabled={mutation.pending}>{mutation.pending ? 'Registering…' : 'Register endpoint'}</button></div></form></Modal>}
  </section>
}

export function KeysView({ workspace }: { workspace: Workspace }) {
  const [create, setCreate] = useState(false)
  const [name, setName] = useState('')
  const [secret, setSecret] = useState<string | null>(null)
  const keys = useResource<ApiKey[]>(workspace.id, signal => api.get(`${base(workspace)}/api-keys`, signal))
  const mutation = useMutation()
  function submit(event: FormEvent) {
    event.preventDefault()
    void mutation.run(() => api.write<CreatedApiKey>(`${base(workspace)}/api-keys`, 'POST', { name: name.trim() }), result => {
      setCreate(false); setName(''); setSecret(result.key); keys.refresh()
    })
  }
  return <section><div className="page-heading"><div><div className="eyebrow">WORKSPACE ACCESS</div><h1>API keys</h1><p>Give your applications permission to submit and inspect events.</p></div><button className="primary" onClick={() => { mutation.clearError(); setCreate(true) }}>+ Create API key</button></div>
    {secret !== null && <Secret kind="API key" value={secret} onClose={() => setSecret(null)} />}
    <ErrorNotice error={!create ? mutation.error : null} />
    <div className="surface"><div className="surface-heading"><div><h2>Application credentials</h2><p>Each key belongs to this workspace. Raw keys appear only at creation.</p></div><button onClick={keys.refresh} disabled={keys.loading}>Refresh API keys</button></div>
      {keys.loading ? <Loading text="Loading API keys…" /> : keys.error ? <ErrorNotice error={keys.error} retry={keys.refresh} /> : keys.data?.length === 0 ? <Empty title="No API keys yet">Create a key for your application, then send events through the REST API.</Empty> : keys.data && <div className="table-scroll"><table data-testid="api-keys-table"><thead><tr><th>Name & public prefix</th><th>Status</th><th>Created</th><th>Access</th></tr></thead><tbody>{keys.data.map(key => <tr key={key.id} data-testid={`key-${key.id}`}><td><strong>{key.name}</strong><code className="sub-id">{key.prefix}</code></td><td><Status value={key.revokedAt ? 'REVOKED' : 'ACTIVE'} /></td><td className="nowrap">{date(key.createdAt)}</td><td>{key.revokedAt ? <span className="muted small">Revoked {date(key.revokedAt)}</span> : <button className="danger-text" disabled={mutation.pending} onClick={() => void mutation.run(() => api.write(`${base(workspace)}/api-keys/${key.id}`, 'DELETE'), keys.refresh)}>Revoke API key</button>}</td></tr>)}</tbody></table></div>}
    </div><div className="context-note"><span aria-hidden="true">↗</span><p>Use <code>Authorization: Bearer &lt;key&gt;</code> with the REST API. Revoked keys stop authenticating immediately.</p></div>
    {create && <Modal title="Create API key" onClose={() => { if (!mutation.pending) setCreate(false) }}><form onSubmit={submit}><label>API key name<input required maxLength={100} autoFocus placeholder="Production application" value={name} onChange={event => setName(event.target.value)} /></label><ErrorNotice error={mutation.error} /><div className="modal-actions"><button type="button" disabled={mutation.pending} onClick={() => setCreate(false)}>Cancel</button><button className="primary" disabled={mutation.pending}>{mutation.pending ? 'Creating…' : 'Create API key'}</button></div></form></Modal>}
  </section>
}

export function EventsView({ workspace }: { workspace: Workspace }) {
  const [offset, setOffset] = useState(0)
  const [draftStatus, setDraftStatus] = useState('')
  const [draftEndpoint, setDraftEndpoint] = useState('')
  const [filters, setFilters] = useState({ status: '', endpoint: '' })
  const [filterError, setFilterError] = useState<string | null>(null)
  const query = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) })
  if (filters.status) query.set('status', filters.status)
  if (filters.endpoint) query.set('endpointId', filters.endpoint)
  const events = useResource<Page<EventView>>(`${workspace.id}:${query}`, signal => api.get(`${base(workspace)}/events?${query}`, signal))
  function apply(event: FormEvent) {
    event.preventDefault()
    const endpoint = draftEndpoint.trim()
    if (endpoint && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(endpoint)) {
      setFilterError('Enter the complete endpoint UUID shown on the Endpoints page.'); return
    }
    setFilterError(null); setFilters({ status: draftStatus, endpoint }); setOffset(0)
  }
  return <section><div className="page-heading"><div><div className="eyebrow">DELIVERY ACTIVITY</div><h1>Events</h1><p>Follow every event from durable acceptance to its latest delivery outcome.</p></div><button onClick={events.refresh} disabled={events.loading}>Refresh events</button></div>
    <div className="surface"><form className="filters" onSubmit={apply}><label>Status<select aria-label="Status" value={draftStatus} onChange={event => setDraftStatus(event.target.value)}><option value="">All statuses</option>{['PENDING', 'IN_FLIGHT', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED'].map(status => <option key={status} value={status}>{status.replaceAll('_', ' ')}</option>)}</select></label><label>Endpoint ID<input value={draftEndpoint} onChange={event => setDraftEndpoint(event.target.value)} placeholder="Any endpoint" spellCheck={false} /></label><button>Apply filters</button>{(filters.status || filters.endpoint) && <button type="button" className="text-button" onClick={() => { setDraftStatus(''); setDraftEndpoint(''); setFilters({ status: '', endpoint: '' }); setOffset(0); setFilterError(null) }}>Clear filters</button>}</form>
      <ErrorNotice error={filterError} />
      {events.loading ? <Loading text="Loading events…" /> : events.error ? <ErrorNotice error={events.error} retry={events.refresh} /> : events.data && <>
        {events.data.items.length === 0 ? <Empty title={filters.status || filters.endpoint ? 'No events match these filters' : 'Your next event starts here'}>{filters.status || filters.endpoint ? 'Try another status or endpoint, or clear the filters.' : <>Register an <a href={routeHref(workspace.id, 'endpoints')}>endpoint</a> and create an <a href={routeHref(workspace.id, 'keys')}>API key</a>, then submit an event through the REST API.</>}</Empty> : <div className="table-scroll"><table className="events-table" data-testid="events-table"><thead><tr><th>Event</th><th>Status</th><th>Endpoint</th><th>Attempts in run</th><th>Accepted</th></tr></thead><tbody>{events.data.items.map(event => <tr key={event.eventId} data-testid={`event-${event.eventId}`}><td><a className="event-link" aria-label={`View event ${event.eventId}`} href={routeHref(workspace.id, 'events', event.eventId)}><code>{shortId(event.eventId)}</code><span aria-hidden="true">↗</span></a><span className="sub-id">Run {event.replayGeneration}</span></td><td><Status value={event.status} /></td><td><code title={event.endpointId}>{shortId(event.endpointId)}</code></td><td>{event.attemptsInRun}<span className="muted"> / {event.maxAttempts}</span></td><td className="nowrap">{date(event.acceptedAt)}</td></tr>)}</tbody></table></div>}
        <Pagination offset={offset} limit={PAGE_SIZE} count={events.data.items.length} hasMore={events.data.hasMore} onChange={setOffset} />
      </>}
    </div><div className="context-note"><span aria-hidden="true">↗</span><p>Refresh to see the latest activity. Delivery attempts may be duplicated; receivers should deduplicate by event ID.</p></div>
  </section>
}

const FAILURE_TEXT: Record<string, string> = {
  HTTP_STATUS: 'The receiver returned an unsuccessful HTTP status.', REQUEST_TIMEOUT: 'The request exceeded its deadline.',
  CONNECT_TIMEOUT: 'The connection could not be established in time.', DNS_ERROR: 'The destination could not be resolved.',
  DESTINATION_BLOCKED: 'The destination was blocked by delivery security rules.', NETWORK_ERROR: 'A network error interrupted delivery.',
  RESOURCE_LIMIT: 'A delivery resource limit was reached.', INTERNAL_ERROR: 'Delivery could not be completed.',
}

export function EventDetail({ workspace, eventId }: { workspace: Workspace; eventId: string }) {
  const [offset, setOffset] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const event = useResource<EventView>(`${workspace.id}:${eventId}`, signal => api.get(`${base(workspace)}/events/${encodeURIComponent(eventId)}`, signal))
  const attempts = useResource<Page<Attempt>>(`${workspace.id}:${eventId}:${offset}`, signal => api.get(`${base(workspace)}/events/${encodeURIComponent(eventId)}/attempts?limit=${PAGE_SIZE}&offset=${offset}`, signal))
  const mutation = useMutation()
  function refresh() { event.refresh(); attempts.refresh() }
  function replay() {
    const generation = event.data?.replayGeneration
    if (generation === undefined || !Number.isSafeInteger(generation) || generation < 0) return
    setNotice(null)
    void mutation.run(async () => {
      try {
        return await api.write<ReplayAcceptance>(`${base(workspace)}/events/${encodeURIComponent(eventId)}/replay`, 'POST', { expectedReplayGeneration: generation })
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 409) {
          refresh()
          throw new Error('The delivery changed or its endpoint is paused. Current details have been refreshed. Review them before replaying again.')
        }
        throw failure
      }
    }, () => { setNotice('Replay queued. Refresh to follow the new delivery run.'); setOffset(0); refresh() })
  }
  const safeGeneration = event.data && Number.isSafeInteger(event.data.replayGeneration) && event.data.replayGeneration >= 0
  return <section data-testid="event-detail"><a className="back-link" href={routeHref(workspace.id, 'events')}>← Back to events</a><div className="page-heading"><div><div className="eyebrow">EVENT DETAILS</div><h1 className="detail-title">One event. Every attempt.</h1><code className="full-id">{eventId}</code></div><button onClick={refresh} disabled={event.loading || attempts.loading}>Refresh details</button></div>
    <ErrorNotice error={mutation.error} />{notice && <div className="info-notice" role="status">{notice}</div>}
    {event.loading ? <Loading text="Loading event details…" /> : event.error ? <ErrorNotice error={event.error} retry={event.refresh} /> : event.data && <div className="surface event-summary"><div className="summary-top"><Status value={event.data.status} /><button className="primary" disabled={mutation.pending || event.data.status !== 'FAILED' || !safeGeneration} onClick={replay}>{mutation.pending ? 'Replaying…' : 'Manual replay'}</button></div><dl className="detail-grid"><div><dt>Accepted</dt><dd>{date(event.data.acceptedAt)}</dd></div><div><dt>Attempts in this run</dt><dd>{event.data.attemptsInRun} / {event.data.maxAttempts}</dd></div><div><dt>Replay generation</dt><dd>{event.data.replayGeneration}</dd></div><div><dt>Next automatic attempt</dt><dd>{date(event.data.nextAttemptAt)}</dd></div><div className="wide-detail"><dt>Endpoint ID</dt><dd><code>{event.data.endpointId}</code></dd></div><div className="wide-detail"><dt>Delivery ID</dt><dd><code>{event.data.deliveryId}</code></dd></div></dl><p className="field-help">{!safeGeneration ? 'This generation cannot be represented safely in the browser. Use a client that supports exact 64-bit integers to replay it.' : event.data.status === 'FAILED' ? 'Manual replay starts a new bounded run and preserves all previous attempts. Its endpoint must be active.' : 'Manual replay becomes available when a delivery reaches its terminal failed state.'}</p></div>}
    <div className="surface attempts-surface"><div className="surface-heading"><div><h2>Attempt history</h2><p>Oldest first. Previous runs stay in the record.</p></div></div>{attempts.loading ? <Loading text="Loading attempt history…" /> : attempts.error ? <ErrorNotice error={attempts.error} retry={attempts.refresh} /> : attempts.data && <>
      {attempts.data.items.length === 0 ? <Empty title="No attempts yet">The event is waiting for the worker to claim its delivery.</Empty> : <div className="table-scroll"><table data-testid="attempts-table"><thead><tr><th>Attempt / run</th><th>Outcome</th><th>HTTP</th><th>Duration</th><th>Started</th><th>Details</th></tr></thead><tbody>{attempts.data.items.map(attempt => <AttemptRows key={attempt.id} attempt={attempt} />)}</tbody></table></div>}
      <Pagination offset={offset} limit={PAGE_SIZE} count={attempts.data.items.length} hasMore={attempts.data.hasMore} onChange={setOffset} />
    </>}</div>
  </section>
}

function AttemptRows({ attempt }: { attempt: Attempt }) {
  return <tr data-testid={`attempt-${attempt.id}`}><td><strong>#{attempt.generation}</strong><span className="sub-id">Run {attempt.replayGeneration} · attempt {attempt.runAttempt}</span></td><td><Status value={attempt.outcome} /></td><td>{attempt.httpStatus ?? '—'}</td><td className="nowrap">{attempt.durationMillis === null ? '—' : `${attempt.durationMillis} ms`}</td><td className="nowrap">{date(attempt.startedAt)}<span className="sub-id">{attempt.finishedAt ? `Finished ${date(attempt.finishedAt)}` : 'In progress'}</span></td><td className="attempt-details">{attempt.failureCode && <><code>{attempt.failureCode}</code><p>{FAILURE_TEXT[attempt.failureCode] ?? 'See the recorded outcome for this attempt.'}</p></>}{attempt.responsePreview !== null && <details><summary>Response preview</summary><pre>{attempt.responsePreview || '(Empty response preview)'}</pre>{attempt.responseTruncated && <span className="field-help">Preview truncated</span>}</details>}{!attempt.failureCode && attempt.responsePreview === null && <span className="muted">{attempt.outcome === 'UNKNOWN' ? 'The lease expired before a result was recorded. The receiver may already have processed this event.' : 'No additional details'}</span>}</td></tr>
}
