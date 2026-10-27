/** No traces, request headers, DOM snapshots, credentials or exception bodies enter the console. */
export default class SafeReporter {
  onStepEnd(_test, _result, step) {
    if (step.error && step.category === 'test.step') console.log(`[E2E] Failed journey stage: ${step.title}`)
  }
  onTestEnd(test, result) {
    console.log(`[E2E] ${result.status}: ${test.title}`)
    if (result.status !== 'passed' && result.errors?.length) {
      const location = result.errors[0].stack?.match(/(core-journey|authorization)\.spec\.ts:(\d+):(\d+)/)
      console.log(`[E2E] Browser assertion failed${location ? ` at ${location[1]}.spec.ts:${location[2]}:${location[3]}` : ''}. Secret-bearing exception messages are suppressed.`)
    }
  }
  onError() { console.error('[E2E] Test runner setup or teardown failed.') }
  onEnd(result) { console.log(`[E2E] Suite ${result.status}. Browser traces, videos and automatic screenshots are disabled.`) }
}
