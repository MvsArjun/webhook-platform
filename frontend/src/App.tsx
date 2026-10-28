import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api, message } from './api'
import AuthPage from './AuthPage'
import { useMutation, useResource } from './hooks'
import { Brand, Empty, ErrorNotice, Loading, Modal } from './ui'
import { EndpointsView, EventDetail, EventsView, KeysView, routeHref } from './WorkspaceViews'
import type { Account, Route, View, Workspace } from './types'

function readRoute(): Route {
  const match = window.location.hash.match(/^#\/workspaces\/([^/]+)\/(events|endpoints|keys)(?:\/([^/]+))?$/)
  if (!match) return { workspaceId: null, view: 'events', eventId: null }
  try { return { workspaceId: decodeURIComponent(match[1]), view: match[2] as View, eventId: match[2] === 'events' && match[3] ? decodeURIComponent(match[3]) : null } }
  catch { return { workspaceId: null, view: 'events', eventId: null } }
}

export default function App() {
  const [account, setAccount] = useState<Account | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  const [epoch, setEpoch] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(null)
    void (async () => {
      await api.refreshCsrf(controller.signal)
      try { return await api.me(controller.signal) }
      catch (failure) { if (failure instanceof ApiError && failure.status === 401) return null; throw failure }
    })().then(value => {
      if (!controller.signal.aborted) { setAccount(value); setLoading(false) }
    }).catch(failure => {
      if (!controller.signal.aborted) { setError(message(failure)); setLoading(false) }
    })
    return () => controller.abort()
  }, [revision])
  useEffect(() => {
    function expired() { api.reset(); setAccount(null); setEpoch(value => value + 1); setNotice('Your session has ended. Sign in again to continue.'); setLoading(false) }
    window.addEventListener('relay:session-expired', expired)
    return () => window.removeEventListener('relay:session-expired', expired)
  }, [])
  if (loading || error) return <div className="boot-screen"><Brand />{loading ? <Loading text="Opening your workspace…" /> : <ErrorNotice error={error} retry={() => setRevision(value => value + 1)} />}</div>
  if (!account) return <AuthPage key={epoch} notice={notice} onLogin={value => { setAccount(value); setNotice(null); setEpoch(current => current + 1) }} />
  return <Dashboard key={`${account.id}:${epoch}`} account={account} onLogout={() => { setAccount(null); setNotice('You have signed out.'); setEpoch(value => value + 1) }} />
}

function Dashboard({ account, onLogout }: { account: Account; onLogout: () => void }) {
  const [route, setRoute] = useState(readRoute)
  const [workspaceDialog, setWorkspaceDialog] = useState<'new' | 'rename' | null>(null)
  const [name, setName] = useState('')
  const workspaces = useResource<Workspace[]>(account.id, signal => api.get('/api/workspaces', signal))
  const mutation = useMutation()
  const logout = useMutation()
  useEffect(() => {
    const change = () => { setRoute(readRoute()); setWorkspaceDialog(null); setName('') }
    window.addEventListener('hashchange', change)
    return () => window.removeEventListener('hashchange', change)
  }, [])
  useEffect(() => {
    if (!route.workspaceId && workspaces.data?.length) window.location.hash = routeHref(workspaces.data[0].id, 'events').slice(1)
  }, [route.workspaceId, workspaces.data])
  const selected = workspaces.data?.find(workspace => workspace.id === route.workspaceId)
  function submitWorkspace(event: FormEvent) {
    event.preventDefault()
    const rename = workspaceDialog === 'rename' && selected
    void mutation.run(() => api.write<Workspace>(rename ? `/api/workspaces/${selected.id}` : '/api/workspaces', rename ? 'PATCH' : 'POST', { name: name.trim() }), workspace => {
      setWorkspaceDialog(null); setName(''); workspaces.refresh()
      if (!rename) window.location.hash = routeHref(workspace.id, 'events').slice(1)
    })
  }
  function openDialog(mode: 'new' | 'rename') {
    mutation.clearError(); setName(mode === 'rename' ? selected?.name ?? '' : ''); setWorkspaceDialog(mode)
  }
  const viewName = { events: 'Events', endpoints: 'Endpoints', keys: 'API keys' }[route.view]
  return <div className="dashboard"><a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); document.getElementById('main-content')?.focus() }}>Skip to content</a><aside className="sidebar"><a className="brand-link" href="#/" aria-label="Relay home"><Brand /></a><div className="workspace-control"><label htmlFor="workspace-select">Current workspace</label><select id="workspace-select" value={selected?.id ?? ''} disabled={!workspaces.data?.length} onChange={event => { setWorkspaceDialog(null); window.location.hash = routeHref(event.target.value, 'events').slice(1) }}><option value="" disabled>{workspaces.loading ? 'Loading…' : 'Select workspace'}</option>{workspaces.data?.map(workspace => <option key={workspace.id} value={workspace.id}>{workspace.name}</option>)}</select><button className="workspace-new" disabled={mutation.pending} onClick={() => openDialog('new')}>+ New workspace</button></div><div className="nav-label">WORKSPACE</div><nav aria-label="Workspace navigation">{(['events', 'endpoints', 'keys'] as View[]).map((view, index) => <a key={view} aria-current={route.view === view ? 'page' : undefined} href={selected ? routeHref(selected.id, view) : '#/'} className={route.view === view ? 'active' : ''}><span aria-hidden="true">{['≋', '↗', '⌘'][index]}</span>{{ events: 'Events', endpoints: 'Endpoints', keys: 'API keys' }[view]}</a>)}</nav><div className="sidebar-bottom"><div className="private-label"><span />PRIVATE WORKSPACE</div><p>Your events. A clear delivery trail.</p><span className="account-email" title={account.email}>{account.email}</span><button onClick={() => void logout.run(() => api.logout(), onLogout)} disabled={logout.pending}>{logout.pending ? 'Signing out…' : 'Sign out'}</button><ErrorNotice error={logout.error} /></div></aside><div className="main-shell"><header className="topbar"><div className="breadcrumb"><span>{selected?.name ?? 'Your workspaces'}</span><span aria-hidden="true">/</span><strong>{viewName}</strong></div><span className="environment"><span />Workspace console</span></header><main id="main-content" tabIndex={-1}><div className="workspace-heading"><span>{selected ? `Up to ${selected.deliveryConcurrencyLimit} concurrent deliveries` : 'A separate home for each application'}</span>{selected && <button className="text-button" disabled={mutation.pending} onClick={() => openDialog('rename')}>Rename workspace</button>}</div>
    {workspaces.loading ? <Loading text="Loading workspaces…" /> : workspaces.error ? <ErrorNotice error={workspaces.error} retry={workspaces.refresh} /> : !workspaces.data?.length ? <div className="surface onboarding"><Empty title="Make room for your first event">Create a workspace to organize your endpoints, API keys and delivery history.</Empty><button className="primary" onClick={() => openDialog('new')}>Create your first workspace</button></div> : !selected ? <div className="surface"><Empty title="Workspace unavailable">This workspace is unavailable or belongs to another account. Choose one of your workspaces from the menu.</Empty></div> : <WorkspacePanel key={selected.id} workspace={selected} route={route} />}
    </main><footer className="dashboard-footer"><span>Relay / Webhook delivery</span><span>Every attempt has a story.</span></footer></div>
    {workspaceDialog && <Modal title={workspaceDialog === 'new' ? 'Create workspace' : 'Rename workspace'} onClose={() => { if (!mutation.pending) setWorkspaceDialog(null) }}><form onSubmit={submitWorkspace}><label>Workspace name<input required autoFocus maxLength={100} value={name} placeholder="My application" onChange={event => setName(event.target.value)} /></label><ErrorNotice error={mutation.error} /><div className="modal-actions"><button type="button" disabled={mutation.pending} onClick={() => setWorkspaceDialog(null)}>Cancel</button><button className="primary" disabled={mutation.pending}>{mutation.pending ? 'Saving…' : workspaceDialog === 'new' ? 'Create workspace' : 'Save workspace name'}</button></div></form></Modal>}
  </div>
}

function WorkspacePanel({ workspace, route }: { workspace: Workspace; route: Route }) {
  if (route.view === 'endpoints') return <EndpointsView workspace={workspace} />
  if (route.view === 'keys') return <KeysView workspace={workspace} />
  return route.eventId ? <EventDetail key={route.eventId} workspace={workspace} eventId={route.eventId} /> : <EventsView workspace={workspace} />
}
