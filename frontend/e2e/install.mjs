import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const child = spawn(process.execPath, [path.join(frontend, 'node_modules/playwright/cli.js'), 'install', 'chromium'], {
  cwd: frontend,
  env: { ...process.env, PLAYWRIGHT_BROWSERS_PATH: process.env.PLAYWRIGHT_BROWSERS_PATH ?? path.resolve(frontend, '../work/playwright-browsers') },
  windowsHide: true,
  stdio: 'inherit',
})
child.on('error', () => { console.error('Could not start the pinned browser installer.'); process.exitCode = 1 })
child.on('exit', code => { process.exitCode = code ?? 1 })
