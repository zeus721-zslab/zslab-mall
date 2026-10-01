import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { subscribeSellerInboxStream, useSellerInboxStream } from '#layers/seller/app/composables/useSellerInboxStream'

// FE-103: 셀러 인박스 변경 신호 구독(관리자 admin-inbox-stream.spec 복제 · 레이어 격리). EventSource를 가짜로 바꿔 검증한다.
const { sellerAuthMock } = vi.hoisted(() => ({ sellerAuthMock: { isAuthenticated: true } }))
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))

class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  static instances: FakeEventSource[] = []

  readyState = FakeEventSource.CONNECTING
  closed = false
  private readonly listeners = new Map<string, Set<() => void>>()

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: () => void): void {
    const set = this.listeners.get(type) ?? new Set<() => void>()
    set.add(listener)
    this.listeners.set(type, set)
  }

  removeEventListener(type: string, listener: () => void): void {
    this.listeners.get(type)?.delete(listener)
  }

  close(): void {
    this.closed = true
    this.readyState = FakeEventSource.CLOSED
  }

  emit(type: string): void {
    this.listeners.get(type)?.forEach((listener) => listener())
  }

  /** 비 200 응답으로 연결이 끝난 상황(브라우저는 재연결하지 않는다). */
  failClosed(): void {
    this.readyState = FakeEventSource.CLOSED
    this.emit('error')
  }

  get listenerCount(): number {
    return [...this.listeners.values()].reduce((sum, set) => sum + set.size, 0)
  }
}

const URL = '/api/v1/seller/inbox/stream'
const RECONNECT_DELAY_MS = 5000

let signedIn = true
const target = { url: URL, isSignedIn: () => signedIn }

async function flush(): Promise<void> {
  for (let index = 0; index < 10; index++) await Promise.resolve()
}

function latest(): FakeEventSource {
  const source = FakeEventSource.instances.at(-1)
  if (!source) throw new Error('EventSource가 열리지 않았다')
  return source
}

function probe(task: () => Promise<void>) {
  return defineComponent({
    setup() {
      useSellerInboxStream(task)
      return () => h('div')
    },
  })
}

const unsubscribers: Array<() => void> = []
function subscribe(task: () => Promise<void>): () => void {
  const unsubscribe = subscribeSellerInboxStream(target, task)
  unsubscribers.push(unsubscribe)
  return unsubscribe
}

describe('useSellerInboxStream (FE-103)', () => {
  beforeEach(() => {
    signedIn = true
    sellerAuthMock.isAuthenticated = true
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-01T03:00:00Z')) // KST 12:00
  })

  afterEach(() => {
    unsubscribers.splice(0).forEach((unsubscribe) => unsubscribe())
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('탭당 연결 1개: 구독자 둘이 같은 연결을 쓰고 신호 1건에 각자 1회 재조회', async () => {
    const badge = vi.fn(async () => {})
    const page = vi.fn(async () => {})
    subscribe(badge)
    subscribe(page)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(latest().url).toBe(URL)
    latest().emit('changed')
    await flush()

    expect(badge).toHaveBeenCalledTimes(1)
    expect(page).toHaveBeenCalledTimes(1)
  })

  it('재조회 진행 중 연속 신호 → 끝난 뒤 1회로 합친다', async () => {
    let finish: () => void = () => {}
    const task = vi.fn(() => new Promise<void>((resolve) => { finish = resolve }))
    subscribe(task)

    latest().emit('changed')
    latest().emit('changed')
    latest().emit('changed')
    latest().emit('changed')
    expect(task).toHaveBeenCalledTimes(1)

    finish()
    await flush()
    expect(task).toHaveBeenCalledTimes(2)
    finish()
    await flush()
    expect(task).toHaveBeenCalledTimes(2)
  })

  it('첫 open은 넘기고 브라우저 자동 재연결 뒤 open에서 1회 재조회', async () => {
    const task = vi.fn(async () => {})
    subscribe(task)

    latest().emit('open')
    await flush()
    expect(task).not.toHaveBeenCalled()

    latest().emit('error') // CONNECTING: 브라우저가 스스로 재연결
    latest().emit('open')
    await flush()
    expect(task).toHaveBeenCalledTimes(1)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('CLOSED → REST 재조회 1회 → 상수 지연 뒤 새 연결 · 다시 닫혀도 반복', async () => {
    const task = vi.fn(async () => {})
    subscribe(task)
    const first = latest()

    first.failClosed()
    await flush()
    expect(task).toHaveBeenCalledTimes(1)
    expect(first.closed).toBe(true)

    vi.advanceTimersByTime(RECONNECT_DELAY_MS - 1)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeEventSource.instances).toHaveLength(2)

    latest().failClosed()
    await flush()
    vi.advanceTimersByTime(RECONNECT_DELAY_MS)
    expect(task).toHaveBeenCalledTimes(2)
    expect(FakeEventSource.instances).toHaveLength(3)
  })

  it('CLOSED → 재조회가 실패해도(비 401 · 배포 중 502 등) 세션이 남아 있으면 지연 뒤 재연결', async () => {
    let badgeCount: number | null = 3
    const task = vi.fn(async () => { badgeCount = null }) // 재조회 실패: 배지를 숨기는 것처럼 작업 안에서 처리
    subscribe(task)

    latest().failClosed()
    await flush()
    expect(badgeCount).toBeNull()

    vi.advanceTimersByTime(RECONNECT_DELAY_MS)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('CLOSED → 재조회가 401(래퍼가 세션 해제·로그인 이동) → 재연결하지 않는다', async () => {
    const task = vi.fn(async () => { signedIn = false })
    subscribe(task)

    latest().failClosed()
    await flush()
    vi.advanceTimersByTime(RECONNECT_DELAY_MS * 10)

    expect(task).toHaveBeenCalledTimes(1)
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('KST 자정에 재조회하고 다음 자정 타이머를 다시 건다', async () => {
    vi.setSystemTime(new Date('2026-10-01T14:59:00Z')) // KST 23:59
    const task = vi.fn(async () => {})
    subscribe(task)

    vi.advanceTimersByTime(60 * 1000 + 1000)
    await flush()
    expect(task).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(24 * 60 * 60 * 1000)
    await flush()
    expect(task).toHaveBeenCalledTimes(2)
  })

  it('화면이 다시 보이면 재조회 · 숨김 전환은 무시', async () => {
    const task = vi.fn(async () => {})
    subscribe(task)
    const visibility = vi.spyOn(document, 'visibilityState', 'get')

    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await flush()
    expect(task).not.toHaveBeenCalled()

    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flush()
    expect(task).toHaveBeenCalledTimes(1)
  })

  it('언마운트: 마지막 구독이 풀리면 연결을 닫고 타이머·화면 보임 감지를 해제한다', async () => {
    const task = vi.fn(async () => {})
    const wrapper = mount(probe(task))
    const source = latest()
    expect(source.url).toBe(URL)
    expect(source.closed).toBe(false)

    wrapper.unmount()
    expect(source.closed).toBe(true)
    expect(source.listenerCount).toBe(0)
    expect(vi.getTimerCount()).toBe(0)

    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(48 * 60 * 60 * 1000)
    await flush()
    expect(task).not.toHaveBeenCalled()
  })

  it('컴포넌트 구독의 세션 판단은 셀러 스토어: 해제 상태면 닫힌 연결을 다시 열지 않는다', async () => {
    const wrapper = mount(probe(vi.fn(async () => { sellerAuthMock.isAuthenticated = false })))

    latest().failClosed()
    await flush()
    vi.advanceTimersByTime(RECONNECT_DELAY_MS * 10)
    expect(FakeEventSource.instances).toHaveLength(1)
    wrapper.unmount()
  })

  it('CLOSED 뒤 재연결 대기 중 언마운트되면 다시 연결하지 않는다', async () => {
    const unsubscribe = subscribe(vi.fn(async () => {}))
    latest().failClosed()
    await flush()

    unsubscribe()
    vi.advanceTimersByTime(RECONNECT_DELAY_MS)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})
