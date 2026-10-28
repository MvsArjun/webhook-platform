import path from 'node:path'

export function check(value) { if (!value) throw new Error('Startup verification failed.') }

export function ownership(id) {
  check(typeof id === 'string' && /^[0-9a-f]{16}$/.test(id))
  const project = `relay-startup-${id}`
  return Object.freeze({ id, project, volume: `${project}_postgres_data` })
}

export function commandEnvironment(environment = process.env) {
  return Object.fromEntries(Object.entries(environment).filter(([name]) =>
    /^(PATH|SYSTEMROOT|WINDIR|COMSPEC|PATHEXT|TEMP|TMP|HOME|USERPROFILE|APPDATA|LOCALAPPDATA|HOMEDRIVE|HOMEPATH|PROCESSOR_ARCHITECTURE|NUMBER_OF_PROCESSORS)$/i.test(name)))
}

export function localDockerEndpoint(endpoint) {
  check(typeof endpoint === 'string' && endpoint.length <= 4096 && !/[\x00-\x20?#]/.test(endpoint))
  check((endpoint.startsWith('unix:///') && !endpoint.slice(8).split('/').includes('..'))
    || endpoint === 'npipe:////./pipe/docker_engine')
  return endpoint
}

export function verifyCompose(config, owner, settings) {
  check(config?.name === owner.project && Object.keys(config.services ?? {}).join() === 'postgres')
  check(Object.keys(config.volumes ?? {}).join() === 'postgres_data')
  check(Object.keys(config.networks ?? {}).join() === 'default')
  const service = config.services.postgres, volume = config.volumes.postgres_data, network = config.networks.default
  check(service.image === 'postgres:17.11-bookworm' && !service.command && !service.entrypoint && !service.privileged)
  check(!service.configs && !service.secrets && !service.container_name)
  check(volume.name === owner.volume && !volume.external && !volume.driver_opts)
  check(network.name === `${owner.project}_default` && !network.external)
  check(service.ports?.length === 1 && service.ports[0].host_ip === '127.0.0.1'
    && String(service.ports[0].published) === String(settings.port) && service.ports[0].target === 5432
    && service.ports[0].protocol === 'tcp')
  check(service.volumes?.length === 1 && service.volumes[0].type === 'volume'
    && service.volumes[0].source === 'postgres_data' && service.volumes[0].target === '/var/lib/postgresql/data')
  check(Object.keys(service.environment ?? {}).sort().join() === 'POSTGRES_DB,POSTGRES_PASSWORD,POSTGRES_USER')
  check(service.environment.POSTGRES_DB === settings.database && service.environment.POSTGRES_USER === settings.user
    && service.environment.POSTGRES_PASSWORD === settings.password)
  check(service.healthcheck?.test?.[0] === 'CMD-SHELL' && service.healthcheck.test[1].includes('pg_isready'))
  return true
}

export function verifyResources(owner, containers, volume = null) {
  check(Array.isArray(containers) && containers.length <= 1)
  for (const container of containers) {
    check(/^[0-9a-f]{64}$/.test(container.Id ?? '')
      && container.Config?.Labels?.['com.docker.compose.project'] === owner.project
      && container.Config.Labels['com.docker.compose.service'] === 'postgres')
  }
  if (volume) check(volume.Name === owner.volume && volume.Labels?.['com.docker.compose.project'] === owner.project
    && volume.Labels['com.docker.compose.volume'] === 'postgres_data')
  return true
}

export function quiescent(state) {
  return state.ownedCommandsStopped === true && state.ownedProcessesStopped === true && state.databaseClosed === true
}

export function ownedFile(root, directory, file, owner) {
  check(ownership(owner.id).project === owner.project)
  const expected = path.resolve(root, 'work/startup', owner.id)
  check(path.resolve(directory) === expected && path.dirname(path.resolve(file)) === expected)
  check(['compose.env', 'signing-master-key.txt'].includes(path.basename(file)))
  return path.resolve(file)
}
