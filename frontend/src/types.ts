export type Account = { id: string; email: string }
export type Workspace = { id: string; name: string; createdAt: string; deliveryConcurrencyLimit: number }
export type Endpoint = { id: string; url: string; paused: boolean; createdAt: string; updatedAt: string }
export type ApiKey = { id: string; name: string; prefix: string; createdAt: string; revokedAt: string | null }
export type CreatedApiKey = ApiKey & { key: string }
export type Page<T> = { items: T[]; limit: number; offset: number; hasMore: boolean }
export type DeliveryStatus = 'PENDING' | 'IN_FLIGHT' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED'
export type EventView = {
  eventId: string; endpointId: string; deliveryId: string; status: DeliveryStatus; acceptedAt: string
  attemptsInRun: number; maxAttempts: number; replayGeneration: number; nextAttemptAt: string | null
}
export type Attempt = {
  id: string; deliveryId: string; generation: number; outcome: string; startedAt: string; finishedAt: string | null
  httpStatus: number | null; failureCode: string | null; durationMillis: number | null
  replayGeneration: number; runAttempt: number; responsePreview: string | null; responseTruncated: boolean
}
export type ReplayAcceptance = { eventId: string; deliveryId: string; replayGeneration: number; status: 'PENDING' }
export type View = 'events' | 'endpoints' | 'keys'
export type Route = { workspaceId: string | null; view: View; eventId: string | null }
