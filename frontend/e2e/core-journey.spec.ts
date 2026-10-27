import { test, expect, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const api = process.env.E2E_API_URL!
const receiver = process.env.E2E_RECEIVER_URL!
const control = process.env.E2E_CONTROL_URL!
const controlHeaders = { 'x-relay-e2e-control': process.env.E2E_CONTROL_TOKEN!, 'Content-Type': 'application/json' }
const preview = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../work/phase6-preview')
type Event = { eventId: string; endpointId: string; deliveryId: string; status: string; replayGeneration: number }

function responseTo(page: Page, resource: string, method: string) {
  return page.waitForResponse(response => new URL(response.url()).pathname === resource && response.request().method() === method)
}

async function noStoredSecrets(page: Page, secrets: string[]) {
  const clean = await page.evaluate(values => {
    const stored = [...Object.values(localStorage), ...Object.values(sessionStorage)]
    return values.every(secret => !stored.some(value => value.includes(secret)) && !document.documentElement.outerHTML.includes(secret)
      && ![...document.querySelectorAll('textarea, input')].some(input => (input as HTMLInputElement).value.includes(secret)))
  }, secrets)
  expect(clean).toBe(true)
}

async function createKey(page: Page, workspace: string, name: string) {
  await page.getByRole('button', { name: '+ Create API key', exact: true }).click()
  const modal = page.getByRole('dialog', { name: 'Create API key', exact: true })
  await modal.getByLabel('API key name', { exact: true }).fill(name)
  const pending = responseTo(page, `/api/workspaces/${workspace}/api-keys`, 'POST')
  await modal.getByRole('button', { name: 'Create API key', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(201)
  const result = await response.json() as { id: string; key: string }
  try {
    await expect(page.getByTestId('api-key-secret')).toBeVisible()
    expect((await page.getByTestId('api-key-secret').inputValue()) === result.key).toBe(true)
    expect(/^rly_[0-9a-f]{32}\.[A-Za-z0-9_-]{43}$/.test(result.key)).toBe(true)
  } finally {
    if (await page.getByRole('button', { name: 'Dismiss API key', exact: true }).isVisible()) {
      await page.getByRole('button', { name: 'Dismiss API key', exact: true }).click()
    } else await page.goto('about:blank')
  }
  await noStoredSecrets(page, [result.key])
  return result
}

async function eventStatus(key: string, id: string): Promise<Event> {
  const response = await fetch(`${api}/api/v1/events/${id}`, { headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(5000) })
  expect(response.status).toBe(200)
  return await response.json() as Event
}

async function screenshot(page: Page, filename: string) {
  await expect(page.getByTestId('api-key-secret')).toHaveCount(0)
  await expect(page.getByTestId('signing-secret')).toHaveCount(0)
  await mkdir(preview, { recursive: true })
  await page.evaluate(() => {
    (document.activeElement as HTMLElement | null)?.blur()
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
    document.querySelectorAll('.table-scroll').forEach(element => element.scrollTo({ top: 0, left: 0, behavior: 'instant' }))
  })
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  if ((page.viewportSize()?.width ?? 0) <= 600) {
    const layout = await page.evaluate(() => ({ documentWidth: document.documentElement.scrollWidth, viewportWidth: window.innerWidth }))
    expect(layout.documentWidth).toBeLessThanOrEqual(layout.viewportWidth + 1)
  }
  await page.screenshot({ path: path.join(preview, filename), fullPage: true, animations: 'disabled' })
}

test('real delivery journey signs requests, preserves failed attempts and manually replays', async ({ page }) => {
  const runtimeErrors: string[] = []
  page.on('pageerror', () => runtimeErrors.push('pageerror'))
  page.on('console', message => {
    if (message.type() === 'error' && !/^Failed to load resource: the server responded with a status of 401/.test(message.text())) runtimeErrors.push('console-error')
  })
  const email = `journey-${randomUUID()}@example.test`
  const password = `Portfolio!${randomUUID()}`
  let workspace = ''
  let endpoint = ''
  let activeKey = ''
  let event: Event
  try {
    await test.step('register and sign in through the real dashboard', async () => {
      await page.goto('/')
      await page.getByRole('button', { name: 'Create an account', exact: true }).click()
      await page.getByLabel('Email', { exact: true }).fill(email)
      await page.getByLabel('Password', { exact: true }).fill(password)
      await page.getByRole('button', { name: 'Create account', exact: true }).click()
      await expect(page.getByText('Account created. Sign in to open your workspaces.', { exact: true })).toBeVisible()
      await page.getByLabel('Password', { exact: true }).fill(password)
      await page.getByRole('button', { name: 'Sign in', exact: true }).click()
      await expect(page.getByTestId('empty-state')).toContainText('Make room for your first event')
    })
    await test.step('create and rename a workspace and observe empty and loading states', async () => {
      await page.getByRole('button', { name: 'Create your first workspace', exact: true }).click()
      const modal = page.getByRole('dialog', { name: 'Create workspace', exact: true })
      await modal.getByLabel('Workspace name', { exact: true }).fill('Portfolio deliveries')
      const pending = responseTo(page, '/api/workspaces', 'POST')
      await modal.getByRole('button', { name: 'Create workspace', exact: true }).click()
      const response = await pending
      expect(response.status()).toBe(201)
      workspace = (await response.json()).id
      await expect(page.getByLabel('Current workspace', { exact: true })).toHaveValue(workspace)
      await expect(page.getByTestId('empty-state')).toContainText('Your next event starts here')
      await page.getByRole('button', { name: 'Rename workspace', exact: true }).click()
      await page.getByRole('dialog').getByLabel('Workspace name', { exact: true }).fill('Portfolio delivery lab')
      await page.getByRole('button', { name: 'Save workspace name', exact: true }).click()
      await expect(page.getByLabel('Current workspace', { exact: true })).toContainText('Portfolio delivery lab')
      // Slow the actual connection; no route interception or fabricated API response is used.
      const cdp = await page.context().newCDPSession(page)
      try {
        await cdp.send('Network.enable')
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 400, downloadThroughput: -1, uploadThroughput: -1 })
        await page.getByRole('link', { name: 'Endpoints', exact: true }).click()
        await expect(page.getByTestId('loading-state')).toBeVisible()
        await expect(page.getByTestId('empty-state')).toContainText('No endpoints on this page')
      } finally {
        await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 })
        await cdp.detach()
      }
    })
    await test.step('register, pause and reveal an endpoint without persisting its secret', async () => {
      await page.getByRole('button', { name: '+ Register endpoint', exact: true }).click()
      const modal = page.getByRole('dialog', { name: 'Register endpoint', exact: true })
      await modal.getByLabel('Endpoint URL', { exact: true }).fill(receiver)
      const pending = responseTo(page, `/api/workspaces/${workspace}/endpoints`, 'POST')
      await modal.getByRole('button', { name: 'Register endpoint', exact: true }).click()
      const response = await pending
      expect(response.status()).toBe(201)
      endpoint = (await response.json()).id
      const row = page.getByTestId(`endpoint-${endpoint}`)
      await row.getByRole('button', { name: 'Reveal signing secret', exact: true }).click()
      let secret = ''
      try {
        await expect(page.getByTestId('signing-secret')).toBeVisible()
        secret = await page.getByTestId('signing-secret').inputValue()
        expect(secret.length > 30).toBe(true)
      } finally { await page.getByRole('button', { name: 'Dismiss signing secret', exact: true }).click() }
      await noStoredSecrets(page, [secret])
      await row.getByRole('button', { name: 'Pause endpoint', exact: true }).click()
      await expect(row).toContainText('PAUSED')
    })
    await test.step('create and revoke application keys through the real dashboard', async () => {
      await page.getByRole('link', { name: 'API keys', exact: true }).click()
      await expect(page.getByTestId('empty-state')).toContainText('No API keys yet')
      activeKey = (await createKey(page, workspace, 'Delivery application')).key
      const revoked = await createKey(page, workspace, 'Temporary application')
      const row = page.getByTestId(`key-${revoked.id}`)
      await row.getByRole('button', { name: 'Revoke API key', exact: true }).click()
      await expect(row).toContainText('REVOKED')
      const denied = await fetch(`${api}/api/v1/workspace`, { headers: { Authorization: `Bearer ${revoked.key}` }, signal: AbortSignal.timeout(5000) })
      expect(denied.status).toBe(401)
      const metadata = await page.request.get(`/api/workspaces/${workspace}/api-keys`)
      expect(metadata.status()).toBe(200)
      const clean = !(await metadata.text()).includes(activeKey) && !(await metadata.text()).includes(revoked.key)
      expect(clean).toBe(true)
      await noStoredSecrets(page, [activeKey, revoked.key])
    })
    await test.step('paused ingestion is rejected, then a real worker records the receiver failure', async () => {
      const submit = () => fetch(`${api}/api/v1/endpoints/${endpoint}/events`, {
        method: 'POST', headers: { Authorization: `Bearer ${activeKey}`, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
        body: JSON.stringify({ kind: 'portfolio.delivery', message: 'Synthetic browser journey' }), signal: AbortSignal.timeout(5000),
      })
      expect((await submit()).status).toBe(409)
      await page.getByRole('link', { name: 'Endpoints', exact: true }).click()
      const row = page.getByTestId(`endpoint-${endpoint}`)
      await row.getByRole('button', { name: 'Resume endpoint', exact: true }).click()
      await expect(row).toContainText('ACTIVE')
      const accepted = await submit()
      expect(accepted.status).toBe(202)
      event = await accepted.json() as Event
      await expect.poll(async () => (await eventStatus(activeKey, event.eventId)).status, { timeout: 20000 }).toBe('FAILED')
      event = await eventStatus(activeKey, event.eventId)
      await page.getByRole('link', { name: 'Events', exact: true }).click()
      await expect(page.getByTestId(`event-${event.eventId}`)).toContainText('FAILED')
      await page.getByRole('link', { name: `View event ${event.eventId}`, exact: true }).click()
      await expect(page.getByTestId('attempts-table').locator('tbody tr')).toHaveCount(1)
      const attempt = page.getByTestId('attempts-table').locator('tbody tr').first()
      await expect(attempt).toContainText('400')
      await attempt.locator('summary').click()
      await expect(attempt.locator('pre')).toContainText('<img data-e2e-inert')
      await expect(page.locator('[data-e2e-inert]')).toHaveCount(0)
      expect(await page.evaluate(() => '__relayUnsafe' in window)).toBe(false)
    })
    await test.step('manual replay preserves history and succeeds with the same event and delivery', async () => {
      const changed = await fetch(control, { method: 'POST', headers: controlHeaders, body: JSON.stringify({ status: 200, body: 'Receiver acknowledged the replay.' }), signal: AbortSignal.timeout(5000) })
      expect(changed.status).toBe(204)
      const pending = responseTo(page, `/api/workspaces/${workspace}/events/${event.eventId}/replay`, 'POST')
      await page.getByRole('button', { name: 'Manual replay', exact: true }).click()
      expect((await pending).status()).toBe(202)
      await expect.poll(async () => (await eventStatus(activeKey, event.eventId)).status, { timeout: 20000 }).toBe('SUCCEEDED')
      const replayed = await eventStatus(activeKey, event.eventId)
      expect(replayed.deliveryId).toBe(event.deliveryId)
      expect(replayed.replayGeneration).toBe(event.replayGeneration + 1)
      await page.getByRole('button', { name: 'Refresh details', exact: true }).click()
      await expect(page.getByTestId('attempts-table').locator('tbody tr')).toHaveCount(2)
      await expect(page.getByTestId('attempts-table').locator('tbody tr').last()).toContainText('200')
      await expect(page.getByTestId('event-detail').locator('.event-summary')).toContainText('SUCCEEDED')
      const received = await fetch(control, { headers: controlHeaders, signal: AbortSignal.timeout(5000) })
      const attempts = (await received.json()).received.filter((item: { eventId: string }) => item.eventId === event.eventId)
      expect(attempts.length).toBe(2)
      expect(attempts.map((item: { status: number }) => item.status)).toEqual([400, 200])
      expect(attempts.every((item: { signed: boolean }) => item.signed)).toBe(true)
      expect(attempts[0].attemptId !== attempts[1].attemptId).toBe(true)
      expect(attempts.map((item: { generation: string }) => item.generation)).toEqual(['1', '2'])
    })
    await test.step('render desktop and mobile delivery views with secrets dismissed', async () => {
      await noStoredSecrets(page, [activeKey])
      await page.setViewportSize({ width: 1440, height: 1050 })
      await screenshot(page, 'event-detail-desktop.png')
      await page.getByRole('link', { name: 'Events', exact: true }).click()
      await expect(page.getByTestId('events-table')).toBeVisible()
      await screenshot(page, 'event-list-desktop.png')
      await page.getByLabel('Status', { exact: true }).selectOption('FAILED')
      await page.getByRole('button', { name: 'Apply filters', exact: true }).click()
      await expect(page.getByTestId('empty-state')).toContainText('No events match these filters')
      await page.getByRole('button', { name: 'Clear filters', exact: true }).click()
      await expect(page.getByTestId('events-table')).toBeVisible()
      await page.setViewportSize({ width: 390, height: 844 })
      const mobileEventCode = page.getByRole('link', { name: `View event ${event.eventId}`, exact: true }).locator('code')
      await expect(mobileEventCode).toBeVisible()
      const codeBounds = await mobileEventCode.boundingBox()
      expect(codeBounds !== null).toBe(true)
      expect(codeBounds!.height).toBeLessThanOrEqual(22)
      await screenshot(page, 'event-list-mobile.png')
      await page.getByRole('link', { name: `View event ${event.eventId}`, exact: true }).click()
      await expect(page.getByTestId('attempts-table').locator('tbody tr')).toHaveCount(2)
      await screenshot(page, 'event-detail-mobile.png')
      expect(runtimeErrors.length).toBe(0)
    })
  } finally {
    // No failure screenshot, trace, storage state, or DOM context may retain a revealed credential.
    await page.goto('about:blank').catch(() => {})
  }
})
