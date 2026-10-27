import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, api } from './api'
import { useMutation } from './hooks'
import { Brand, ErrorNotice } from './ui'
import type { Account } from './types'

export default function AuthPage({ onLogin, notice }: { onLogin: (account: Account) => void; notice: string | null }) {
  const [register, setRegister] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const mutation = useMutation()
  function submit(event: FormEvent) {
    event.preventDefault()
    setNote(null)
    void mutation.run(async () => {
      if (register && (password.length < 12 || new TextEncoder().encode(password).length > 72)) {
        throw new Error('Use at least 12 characters and no more than 72 UTF-8 bytes for your password.')
      }
      try {
        if (register) { await api.register(email.trim(), password); return null }
        return await api.login(email.trim(), password)
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) throw new Error('Email or password is incorrect.')
        if (error instanceof ApiError && error.status === 409) throw new Error('An account with this email already exists. Sign in instead.')
        throw error
      }
    }, account => {
      setPassword('')
      if (account) onLogin(account)
      else { setRegister(false); setNote('Account created. Sign in to open your workspaces.') }
    })
  }
  return <div className="auth-page"><section className="auth-story"><a href="#/" aria-label="Relay home"><Brand /></a><div><div className="eyebrow">WEBHOOK DELIVERY, WITH A PAPER TRAIL</div><h1>Every event.<br />Every attempt.<br /><span>In the clear.</span></h1><p>Give your webhooks a dependable home. Manage destinations, follow delivery attempts, and put failed events back in motion.</p><div className="story-line"><span />Durable events · Signed deliveries · Bounded retries</div></div><span className="auth-footnote">Relay / Developer workspace</span></section><main className="auth-panel"><div className="auth-form-wrap"><div className="eyebrow">WELCOME TO RELAY</div><h2>{register ? 'Create your account' : 'Welcome back.'}</h2><p className="muted">{register ? 'Your first workspace is one step away.' : 'Sign in to pick up where your events left off.'}</p>{(note || notice) && <div className="info-notice" role="status">{note ?? notice}</div>}<ErrorNotice error={mutation.error} /><form onSubmit={submit}><label>Email<input type="email" autoComplete="email" required maxLength={320} value={email} onChange={event => setEmail(event.target.value)} /></label><label>Password<input type="password" autoComplete={register ? 'new-password' : 'current-password'} required value={password} onChange={event => setPassword(event.target.value)} /></label>{register && <p className="field-help">At least 12 characters; up to 72 UTF-8 bytes. Spaces are preserved.</p>}<button className="primary wide" disabled={mutation.pending}>{mutation.pending ? 'Please wait…' : register ? 'Create account' : 'Sign in'}<span aria-hidden="true">↗</span></button></form><div className="auth-switch">{register ? 'Already have an account?' : 'New to Relay?'} <button className="text-button" onClick={() => { setRegister(!register); mutation.clearError(); setPassword(''); setNote(null) }}>{register ? 'Back to sign in' : 'Create an account'}</button></div><p className="auth-privacy">Your keys and signing secrets stay private. This dashboard uses a browser session.</p></div></main></div>
}
