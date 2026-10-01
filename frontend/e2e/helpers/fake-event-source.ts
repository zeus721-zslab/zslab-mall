import type { Page } from '@playwright/test'

/**
 * 인박스 변경 신호(FE-103) E2E용 가짜 EventSource. 실 SSE 대신 브라우저 전역 EventSource를 바꿔 끼워, 열린 연결 목록을 남기고 테스트가 신호를
 * 직접 발생시킨다. 신호를 받은 화면이 재조회 요청을 보내는지만 확인한다(실 BE 스트림 동작은 BE 통합 테스트 · D-249).
 */
declare global {
  interface Window {
    __inboxStreams: Array<EventTarget & { url: string; readyState: number }>
  }
}

const CLOSED = 2

export async function installFakeEventSource(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const streams: Window['__inboxStreams'] = []
    class FakeEventSource extends EventTarget {
      static readonly CONNECTING = 0
      static readonly OPEN = 1
      static readonly CLOSED = 2
      readyState = 0
      readonly url: string

      constructor(url: string) {
        super()
        this.url = url
        streams.push(this)
        setTimeout(() => {
          this.readyState = 1
          this.dispatchEvent(new Event('open'))
        }, 0)
      }

      close(): void {
        this.readyState = 2
      }
    }
    window.__inboxStreams = streams
    Object.defineProperty(window, 'EventSource', { value: FakeEventSource, configurable: true, writable: true })
  })
}

/** 지금 열려 있는 연결의 URL 목록. */
export function openStreamUrls(page: Page): Promise<string[]> {
  return page.evaluate((closed) => window.__inboxStreams.filter((stream) => stream.readyState !== closed).map((stream) => stream.url), CLOSED)
}

/** 열린 모든 연결에 "changed" 신호를 보낸다. */
export async function emitInboxSignal(page: Page): Promise<void> {
  await page.evaluate((closed) => {
    window.__inboxStreams
      .filter((stream) => stream.readyState !== closed)
      .forEach((stream) => stream.dispatchEvent(new MessageEvent('changed', { data: '1' })))
  }, CLOSED)
}
