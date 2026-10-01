import { test as base } from '@playwright/test'
import { installFakeEventSource } from './helpers/fake-event-source'

/**
 * e2e 공통 픽스처(FE-103). 관리자·셀러 레이아웃은 인박스 변경 신호 SSE를 상시 열어 두므로 실 연결이면 networkidle 대기가 끝나지 않는다 —
 * 모든 page에 가짜 EventSource를 자동 주입한다. 신호 동작이 필요한 spec은 helpers/fake-event-source의 emitInboxSignal로 신호를 낸다.
 * 실 스트림 동작은 BE 통합 테스트(D-249)가 검증한다.
 */
export * from '@playwright/test'

export const test = base.extend<{ fakeEventSource: void }>({
  fakeEventSource: [async ({ page }, use) => {
    await installFakeEventSource(page)
    await use()
  }, { auto: true }],
})
