import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { ownership, commandEnvironment, localDockerEndpoint, verifyCompose, verifyResources, ownedFile, quiescent } from './support.mjs'

const owner = ownership('0123456789abcdef')
const settings = { port: 55432, database: 'disposable_startup', user: 'disposable', password: 'synthetic-fixture-only' }
function configuration() {
  return { name: owner.project, services: { postgres: { image: 'postgres:17.11-bookworm',
    ports: [{ host_ip: '127.0.0.1', published: '55432', target: 5432, protocol: 'tcp' }],
    volumes: [{ type: 'volume', source: 'postgres_data', target: '/var/lib/postgresql/data' }],
    environment: { POSTGRES_DB: settings.database, POSTGRES_USER: settings.user, POSTGRES_PASSWORD: settings.password },
    healthcheck: { test: ['CMD-SHELL', 'pg_isready'] } } },
    volumes: { postgres_data: { name: owner.volume } }, networks: { default: { name: `${owner.project}_default` } } }
}

test('ownership and deletion boundaries reject aliases, unrelated projects and paths', () => {
  for (const id of ['', '../0123456789abcdef', '0123456789ABCDEF', '0123456789abcdef0']) assert.throws(() => ownership(id))
  const root = path.resolve('synthetic-workspace'), directory = path.join(root, 'work/startup', owner.id)
  assert.equal(ownedFile(root, directory, path.join(directory, 'compose.env'), owner), path.join(directory, 'compose.env'))
  assert.throws(() => ownedFile(root, directory, path.join(root, '.env'), owner))
  assert.throws(() => ownedFile(root, directory, path.join(directory, 'unrelated.txt'), owner))
  assert.throws(() => ownedFile(root, path.join(root, 'work/startup/other'), path.join(directory, 'compose.env'), owner))
})

test('Docker endpoint and child environment cannot inherit remote or compose overrides', () => {
  assert.equal(localDockerEndpoint('unix:///var/run/docker.sock'), 'unix:///var/run/docker.sock')
  assert.equal(localDockerEndpoint('npipe:////./pipe/docker_engine'), 'npipe:////./pipe/docker_engine')
  for (const endpoint of ['tcp://127.0.0.1:2375', 'ssh://remote', 'unix:///tmp/../docker.sock', 'unix:///tmp/socket?x', 'npipe:////remote/pipe/docker_engine']) {
    assert.throws(() => localDockerEndpoint(endpoint))
  }
  assert.deepEqual(commandEnvironment({ PATH: '/synthetic/bin', HOME: '/synthetic/user', DOCKER_HOST: 'remote',
    COMPOSE_PROJECT_NAME: 'user-project', POSTGRES_PASSWORD: 'private', JAVA_TOOL_OPTIONS: 'override', TOKEN: 'private' }),
  { PATH: '/synthetic/bin', HOME: '/synthetic/user' })
})

test('effective Compose configuration refuses public binds, foreign storage and extra services', () => {
  assert.equal(verifyCompose(configuration(), owner, settings), true)
  for (const mutate of [c => { c.services.postgres.ports[0].host_ip = '0.0.0.0' },
    c => { c.volumes.postgres_data.name = 'user-data' }, c => { c.volumes.postgres_data.external = true },
    c => { c.services.postgres.volumes[0].type = 'bind' }, c => { c.services.other = {} },
    c => { c.networks.default.external = true }, c => { c.services.postgres.environment.POSTGRES_PASSWORD = 'wrong' }]) {
    const config = configuration(); mutate(config); assert.throws(() => verifyCompose(config, owner, settings))
  }
})

test('cleanup requires matching project labels on every container and named volume', () => {
  const stopped = { ownedCommandsStopped: true, ownedProcessesStopped: true, databaseClosed: true }
  assert.equal(quiescent(stopped), true)
  for (const key of Object.keys(stopped)) {
    assert.equal(quiescent({ ...stopped, [key]: false }), false)
    assert.equal(quiescent({ ...stopped, [key]: 'true' }), false)
  }
  const container = { Id: 'a'.repeat(64), Config: { Labels: { 'com.docker.compose.project': owner.project,
    'com.docker.compose.service': 'postgres' } } }
  const volume = { Name: owner.volume, Labels: { 'com.docker.compose.project': owner.project, 'com.docker.compose.volume': 'postgres_data' } }
  assert.equal(verifyResources(owner, [container], volume), true)
  assert.throws(() => verifyResources(owner, [container], { ...volume, Name: 'foreign-data' }))
  assert.throws(() => verifyResources(owner, [{ ...container, Config: { Labels: { 'com.docker.compose.project': 'foreign' } } }], volume))
  assert.throws(() => verifyResources(owner, [container, container], volume))
})
