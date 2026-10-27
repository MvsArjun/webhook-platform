import { expect, request, test } from '@playwright/test'
import type { APIResponse, BrowserContext, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'

function setting(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`Missing E2E setting: ${name}`)
  return value
}

async function status(response: APIResponse, expected: number) {
  expect(response.status()).toBe(expected)
}

async function csrf(context: BrowserContext): Promise<string> {
  const response = await context.request.get(`${setting('E2E_API_URL')}/api/auth/csrf`)
  await status(response, 200)
  return (await response.json()).token as string
}

async function write(context: BrowserContext, method: string, path: string, data?: unknown) {
  const token = await csrf(context)
  return context.request.fetch(`${setting('E2E_API_URL')}${path}`, {
    method, headers: { 'X-CSRF-TOKEN': token }, ...(data === undefined ? {} : { data }),
  })
}

async function registerAndLoginApi(context: BrowserContext) {
  const email = `authorization-${randomUUID()}@example.test`
  const password = `E2E authorization ${randomUUID()}!`
  await status(await write(context, 'POST', '/api/auth/register', { email, password }), 201)
  const token = await csrf(context)
  await status(await context.request.post(`${setting('E2E_API_URL')}/api/auth/login`, {
    form: { email, password }, headers: { 'X-CSRF-TOKEN': token },
  }), 204)
  await csrf(context)
}

async function registerAndLoginUi(page: Page) {
  const email = `authorization-ui-${randomUUID()}@example.test`
  const password = `E2E authorization ${randomUUID()}!`
  await page.goto(setting('E2E_UI_URL'))
  await page.getByRole('button', { name: 'Create an account', exact: true }).click()
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByText('Account created. Sign in to open your workspaces.')).toBeVisible()
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
}

async function createWorkspace(context: BrowserContext, name: string) {
  const response = await write(context, 'POST', '/api/workspaces', { name })
  await status(response, 201)
  return await response.json() as { id: string; name: string }
}

function url(workspace: string, view: string, event?: string) {
  return `${setting('E2E_UI_URL')}/#/workspaces/${workspace}/${view}${event ? `/${event}` : ''}`
}

test('real sessions isolate workspace deep links and clear revealed credentials across workspace changes and logout', async ({ browser }) => {
  const owner = await browser.newContext()
  const outsider = await browser.newContext()
  const pageA = await owner.newPage()
  const pageB = await outsider.newPage()
  let replay: Awaited<ReturnType<typeof request.newContext>> | null = null
  try {
    await registerAndLoginApi(owner)
    const privateName = `Private app ${randomUUID().slice(0, 8)}`
    const workspaceA = await createWorkspace(owner, privateName)
    const workspaceA2 = await createWorkspace(owner, `Other app ${randomUUID().slice(0, 8)}`)
    const endpointResponse = await write(owner, 'POST', `/api/workspaces/${workspaceA.id}/endpoints`, { url: setting('E2E_RECEIVER_URL') })
    await status(endpointResponse, 201)
    const endpoint = await endpointResponse.json() as { id: string }
    const keyResponse = await write(owner, 'POST', `/api/workspaces/${workspaceA.id}/api-keys`, { name: 'Private application' })
    await status(keyResponse, 201)
    const keyA = await keyResponse.json() as { id: string; key: string }
    const eventResponse = await owner.request.post(`${setting('E2E_API_URL')}/api/v1/endpoints/${endpoint.id}/events`, {
      headers: { Authorization: `Bearer ${keyA.key}`, 'Content-Type': 'application/json' },
      data: { type: 'authorization.probe' },
    })
    await status(eventResponse, 202)
    const event = await eventResponse.json() as { eventId: string; deliveryId: string }
    await pageA.goto(url(workspaceA.id, 'events'))
    await expect(pageA.getByTestId(`event-${event.eventId}`)).toBeVisible()

    await registerAndLoginUi(pageB)
    const workspaceB = await createWorkspace(outsider, `Separate app ${randomUUID().slice(0, 8)}`)
    await pageB.goto(url(workspaceA.id, 'events'))
    // API fixture setup changed the workspace list outside this already-mounted dashboard.
    await pageB.reload()
    await expect(pageB.getByRole('heading', { name: 'Workspace unavailable', exact: true })).toBeVisible()
    let text = await pageB.locator('body').innerText()
    expect(text.includes(privateName)).toBe(false)
    expect(text.includes(keyA.key)).toBe(false)
    expect(text.includes(event.deliveryId)).toBe(false)

    for (const path of [
      `/api/workspaces/${workspaceA.id}/events`, `/api/workspaces/${workspaceA.id}/events/${event.eventId}`,
      `/api/workspaces/${workspaceA.id}/endpoints`, `/api/workspaces/${workspaceA.id}/api-keys`,
    ]) await status(await outsider.request.get(`${setting('E2E_API_URL')}${path}`), 404)
    await status(await write(outsider, 'PATCH', `/api/workspaces/${workspaceA.id}`, { name: 'Unauthorized rename' }), 404)
    await status(await write(outsider, 'PATCH', `/api/workspaces/${workspaceA.id}/endpoints/${endpoint.id}`, { paused: true }), 404)
    await status(await write(outsider, 'DELETE', `/api/workspaces/${workspaceA.id}/api-keys/${keyA.id}`), 404)
    await status(await write(outsider, 'POST', `/api/workspaces/${workspaceA.id}/events/${event.eventId}/replay`, { expectedReplayGeneration: 0 }), 404)
    await status(await write(outsider, 'POST', `/api/workspaces/${workspaceA.id}/endpoints/${endpoint.id}/signing-secret`), 404)
    const ownKeyResponse = await write(outsider, 'POST', `/api/workspaces/${workspaceB.id}/api-keys`, { name: 'Own API client' })
    await status(ownKeyResponse, 201)
    const ownKey = (await ownKeyResponse.json()).key as string
    await status(await outsider.request.get(`${setting('E2E_API_URL')}/api/v1/events/${event.eventId}`, {
      headers: { Authorization: `Bearer ${ownKey}` },
    }), 404)
    await status(await outsider.request.post(`${setting('E2E_API_URL')}/api/v1/endpoints/${endpoint.id}/events`, {
      headers: { Authorization: `Bearer ${ownKey}`, 'Content-Type': 'application/json' }, data: {},
    }), 404)
    const untouchedEndpoint = await owner.request.get(`${setting('E2E_API_URL')}/api/workspaces/${workspaceA.id}/endpoints/${endpoint.id}`)
    await status(untouchedEndpoint, 200)
    expect((await untouchedEndpoint.json()).paused).toBe(false)

    // Knowing another event ID does not allow reading it through an owned workspace route.
    await pageB.goto(url(workspaceB.id, 'events', event.eventId))
    await expect(pageB.getByTestId('error-state').first()).toBeVisible()
    text = await pageB.locator('body').innerText()
    expect(text.includes(privateName)).toBe(false)
    expect(text.includes(event.deliveryId)).toBe(false)
    expect(text.includes(keyA.key)).toBe(false)
    await pageB.goto(url(workspaceB.id, 'events', randomUUID()))
    await expect(pageB.getByTestId('error-state').first()).toBeVisible()

    await pageA.goto(url(workspaceA.id, 'keys'))
    await pageA.getByRole('button', { name: '+ Create API key', exact: true }).click()
    const dialog = pageA.getByRole('dialog')
    await dialog.getByLabel('API key name', { exact: true }).fill('Transient reveal')
    await dialog.getByRole('button', { name: 'Create API key', exact: true }).click()
    const keySecret = pageA.getByTestId('api-key-secret')
    await expect(keySecret).toBeVisible()
    const transientKey = await keySecret.inputValue()
    expect(transientKey.length > 40).toBe(true)
    await pageA.getByLabel('Current workspace', { exact: true }).selectOption(workspaceA2.id)
    await expect(keySecret).toHaveCount(0)
    expect((await pageA.locator('body').innerText()).includes(transientKey)).toBe(false)
    await pageA.goto(url(workspaceA.id, 'keys'))
    await expect(keySecret).toHaveCount(0)

    await pageA.goto(url(workspaceA.id, 'endpoints'))
    await pageA.getByTestId(`endpoint-${endpoint.id}`).getByRole('button', { name: 'Reveal signing secret', exact: true }).click()
    const signingSecret = pageA.getByTestId('signing-secret')
    await expect(signingSecret).toBeVisible()
    const secret = await signingSecret.inputValue()
    const oldCookie = (await owner.cookies(setting('E2E_API_URL'))).find(cookie => cookie.name === 'RELAY_SESSION')?.value
    expect(typeof oldCookie === 'string').toBe(true)
    await pageA.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(pageA.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible()
    await expect(signingSecret).toHaveCount(0)
    expect((await pageA.locator('body').innerText()).includes(secret)).toBe(false)
    await status(await owner.request.get(`${setting('E2E_API_URL')}/api/auth/me`), 401)
    replay = await request.newContext({ extraHTTPHeaders: { Cookie: `RELAY_SESSION=${oldCookie}` } })
    await status(await replay.get(`${setting('E2E_API_URL')}/api/auth/me`), 401)
    expect(await pageA.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
    await pageB.goto(url(workspaceB.id, 'events'))
    await expect(pageB.getByLabel('Current workspace', { exact: true })).toHaveValue(workspaceB.id)
    await expect(pageB.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
    await expect(pageB.getByTestId('empty-state')).toBeVisible()
    await outsider.setOffline(true)
    await pageB.getByRole('button', { name: 'Refresh events', exact: true }).click()
    await expect(pageB.getByTestId('error-state')).toContainText('Cannot connect to Relay')
    await outsider.setOffline(false)
    await pageB.getByTestId('error-state').getByRole('button', { name: 'Try again', exact: true }).click()
    await expect(pageB.getByTestId('empty-state')).toBeVisible()
    await expect(pageB.getByTestId('error-state')).toHaveCount(0)
  } finally {
    await replay?.dispose()
    await Promise.allSettled([pageA.goto('about:blank'), pageB.goto('about:blank')])
    await Promise.allSettled([owner.close(), outsider.close()])
  }
})
