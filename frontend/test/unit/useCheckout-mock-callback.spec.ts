import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useCheckout } from '~/composables/useCheckout'

// Track 93 D-198: mock 결제 콜백은 인가된 endpoint(/v1/payments/mock-callback)로 attemptKey·callbackType만 보낸다.
// D-235: 인증(구매자 쿠키)·CSRF 헤더·base는 구매자 래퍼(useBuyerApi)가 맡으므로 래퍼를 mock해 경로·메서드·body만 검증한다(응답은 200 void).
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn(async () => undefined) }))
mockNuxtImport('useBuyerApi', () => () => fetchMock)

interface FetchCall {
  path: string
  options: { method: string, headers?: Record<string, string>, body: Record<string, unknown> }
}

function lastCall(): FetchCall {
  const call = fetchMock.mock.calls.at(-1) as unknown as [string, FetchCall['options']]
  return { path: call[0], options: call[1] }
}

describe('useCheckout.sendPaymentCallback (Track 93 mock 결제 인가 endpoint)', () => {
  beforeEach(() => {
    fetchMock.mockClear()
  })

  it('SUCCESS → 구매자 래퍼로 POST /v1/payments/mock-callback · Authorization 미주입 · body는 attemptKey·callbackType만', async () => {
    await useCheckout().sendPaymentCallback({ attemptKey: 'pat_01TEST', callbackType: 'SUCCESS' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const { path, options } = lastCall()
    expect(path).toBe('/v1/payments/mock-callback')
    expect(options.method).toBe('POST')
    expect(options.headers).toBeUndefined()
    expect(options.body).toEqual({ attemptKey: 'pat_01TEST', callbackType: 'SUCCESS' })
  })

  it('payload에 pgTid·provider·occurredAt·metadata 부재(서버 생성 필드)', async () => {
    await useCheckout().sendPaymentCallback({ attemptKey: 'pat_01TEST', callbackType: 'FAILURE' })

    const { path, options } = lastCall()
    expect(path).not.toContain('webhooks')
    expect(Object.keys(options.body).sort()).toEqual(['attemptKey', 'callbackType'])
    expect(options.body).not.toHaveProperty('pgTid')
    expect(options.body).not.toHaveProperty('provider')
    expect(options.body).not.toHaveProperty('occurredAt')
    expect(options.body).not.toHaveProperty('metadata')
  })

  it('4xx/5xx는 throw해 호출부가 처리한다', async () => {
    fetchMock.mockRejectedValueOnce(new Error('422'))
    await expect(useCheckout().sendPaymentCallback({ attemptKey: 'pat_01TEST', callbackType: 'CANCEL' })).rejects.toThrow('422')
  })
})
