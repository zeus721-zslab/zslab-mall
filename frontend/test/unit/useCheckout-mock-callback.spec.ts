import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useCheckout } from '~/composables/useCheckout'

// Track 93 D-198: mock 결제 콜백은 인가된 endpoint(/v1/payments/mock-callback·Bearer)로 attemptKey·callbackType만 보낸다.
// 네트워크는 $fetch를 mock해 경로·헤더·body만 검증한다(응답은 200 void). runtimeConfig는 실값(apiBase 미설정 → '/api' fallback)을 쓴다.
const { authMock } = vi.hoisted(() => ({
  authMock: { token: 'buyer-jwt-token' },
}))

mockNuxtImport('useAuthStore', () => () => authMock)

// nuxt 4.5부터 $fetch는 auto-import(모듈 로드 시 globalThis.$fetch 고정)라 전역 stub이 닿지 않는다(FE-90).
const { fetchMock } = vi.hoisted(() => ({ fetchMock: vi.fn(async () => undefined) }))
mockNuxtImport('$fetch', () => fetchMock)

interface FetchCall {
  path: string
  options: { baseURL: string, method: string, headers: Record<string, string>, body: Record<string, unknown> }
}

function lastCall(): FetchCall {
  const call = fetchMock.mock.calls.at(-1) as unknown as [string, FetchCall['options']]
  return { path: call[0], options: call[1] }
}

describe('useCheckout.sendPaymentCallback (Track 93 mock 결제 인가 endpoint)', () => {
  beforeEach(() => {
    fetchMock.mockClear()
  })

  it('SUCCESS → POST /v1/payments/mock-callback · Bearer 헤더 · body는 attemptKey·callbackType만', async () => {
    await useCheckout().sendPaymentCallback({ attemptKey: 'pat_01TEST', callbackType: 'SUCCESS' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const { path, options } = lastCall()
    expect(path).toBe('/v1/payments/mock-callback')
    expect(options.method).toBe('POST')
    expect(options.baseURL).toBe('/api')
    expect(options.headers.Authorization).toBe('Bearer buyer-jwt-token')
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
