import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { FetchContext, FetchResponse } from 'ofetch'
import { useSellerApi } from '#layers/seller/app/composables/useSellerApi'

// $fetch.create에 넘긴 옵션(baseURL·onRequest·onResponseError)을 붙잡아 훅을 직접 호출·검증한다(실 네트워크 없음).
// D-235: Authorization은 싣지 않고(쿠키 인증) unsafe 요청에만 CSRF 헤더를 싣는다.
// D-190 분기: 401은 셀러 상태만 비우고 셀러 로그인으로, 403 SELLER_SUSPENDED는 로그아웃 없이 정지 안내 플래그만, 그 외 403은 호출부 처리.
const { sellerAuthMock, userAuthMock, navigateToMock } = vi.hoisted(() => ({
  sellerAuthMock: { clearSession: vi.fn(), markSuspended: vi.fn() },
  userAuthMock: { clearSession: vi.fn() },
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
mockNuxtImport('useAuthStore', () => () => userAuthMock)
mockNuxtImport('navigateTo', () => navigateToMock)

type CreateOptions = Parameters<typeof $fetch.create>[0]
// nuxt 4.5부터 $fetch는 auto-import(모듈 로드 시 globalThis.$fetch 고정)라 전역 stub이 닿지 않는다(FE-90).
const { createMock } = vi.hoisted(() => ({ createMock: vi.fn<(options: CreateOptions) => typeof $fetch>() }))
mockNuxtImport('$fetch', () => Object.assign(vi.fn(), { create: createMock }))

function capturedOptions(): CreateOptions {
  useSellerApi()
  const options = createMock.mock.calls[0]?.[0]
  if (!options) throw new Error('$fetch.create 미호출')
  return options
}

/** onResponseError 훅에 넘길 최소 컨텍스트. status와 파싱된 본문(_data)만 소비한다. */
function responseErrorContext(status: number, body?: unknown): FetchContext & { response: FetchResponse<unknown> } {
  return { request: '/v1/seller/settlements', options: {}, response: { status, _data: body } as FetchResponse<unknown> } as FetchContext & {
    response: FetchResponse<unknown>
  }
}

async function fireResponseError(status: number, body?: unknown): Promise<void> {
  const onResponseError = capturedOptions().onResponseError
  if (typeof onResponseError !== 'function') throw new Error('onResponseError 미정의')
  await onResponseError(responseErrorContext(status, body))
}

describe('useSellerApi', () => {
  beforeEach(() => {
    createMock.mockReset()
    createMock.mockReturnValue(vi.fn() as unknown as typeof $fetch)
    sellerAuthMock.clearSession.mockReset()
    sellerAuthMock.markSuspended.mockReset()
    userAuthMock.clearSession.mockReset()
    navigateToMock.mockReset()
  })

  it('baseURL은 브라우저 상대경로(/api)', () => {
    expect(capturedOptions().baseURL).toBe('/api')
  })

  it('onRequest → Authorization 없음 · unsafe 요청만 XSRF-TOKEN 쿠키 원문을 X-XSRF-TOKEN으로(F2·F3)', () => {
    const onRequest = capturedOptions().onRequest
    if (typeof onRequest !== 'function') throw new Error('onRequest 미정의')
    document.cookie = 'XSRF-TOKEN=xsrf-value; path=/'
    const patchHeaders = new Headers()
    onRequest({ request: '/v1/seller/me/password', options: { method: 'PATCH', headers: patchHeaders } } as FetchContext)
    expect(patchHeaders.get('Authorization')).toBeNull()
    expect(patchHeaders.get('X-XSRF-TOKEN')).toBe('xsrf-value')
    const getHeaders = new Headers()
    onRequest({ request: '/v1/seller/me', options: { headers: getHeaders } } as FetchContext)
    expect(getHeaders.get('X-XSRF-TOKEN')).toBeNull()
  })

  it('401 → 셀러 상태만 해제 + /seller/login 이동(사용자 auth 스토어 미호출·정지 플래그 미변경)', async () => {
    await fireResponseError(401)
    expect(sellerAuthMock.clearSession).toHaveBeenCalledTimes(1)
    expect(userAuthMock.clearSession).not.toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/seller/login')
    expect(sellerAuthMock.markSuspended).not.toHaveBeenCalled()
  })

  it('403 SELLER_SUSPENDED → 정지 안내 플래그만 켜고 세션 유지(logout·이동 없음)', async () => {
    await fireResponseError(403, { code: 'SELLER_SUSPENDED', status: 403, detail: '정지 상태의 셀러는 해당 작업을 수행할 수 없습니다.' })
    expect(sellerAuthMock.markSuspended).toHaveBeenCalledTimes(1)
    expect(sellerAuthMock.clearSession).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('403 다른 코드(FORBIDDEN)·본문 없음 → 세션 유지·플래그 미변경(호출부 처리)', async () => {
    await fireResponseError(403, { code: 'FORBIDDEN' })
    await fireResponseError(403)
    expect(sellerAuthMock.markSuspended).not.toHaveBeenCalled()
    expect(sellerAuthMock.clearSession).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
