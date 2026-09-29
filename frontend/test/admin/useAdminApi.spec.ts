import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { FetchContext, FetchResponse } from 'ofetch'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

// $fetch.create에 넘긴 옵션(baseURL·onRequest·onResponseError)을 붙잡아 훅을 직접 호출·검증한다(실 네트워크 없음).
// FE-22d·D-235: Authorization은 싣지 않고(쿠키 인증) unsafe 요청에만 CSRF 헤더를 싣는다. 401 처리는 관리자 세션 스토어만 다루고 사용자 auth 스토어는 건드리지 않는다(401 격리).
const { authMock, userAuthMock, navigateToMock } = vi.hoisted(() => ({
  authMock: { clearSession: vi.fn() },
  userAuthMock: { clearSession: vi.fn() },
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/admin/app/stores/adminAuth', () => ({ useAdminAuthStore: () => authMock }))
mockNuxtImport('useAuthStore', () => () => userAuthMock)
mockNuxtImport('navigateTo', () => navigateToMock)

type CreateOptions = Parameters<typeof $fetch.create>[0]
// nuxt 4.5부터 $fetch는 auto-import(모듈 로드 시 globalThis.$fetch 고정)라 전역 stub이 닿지 않는다(FE-90).
const { createMock } = vi.hoisted(() => ({ createMock: vi.fn<(options: CreateOptions) => typeof $fetch>() }))
mockNuxtImport('$fetch', () => Object.assign(vi.fn(), { create: createMock }))

function capturedOptions(): CreateOptions {
  useAdminApi()
  const options = createMock.mock.calls[0]?.[0]
  if (!options) throw new Error('$fetch.create 미호출')
  return options
}

/** onResponseError 훅에 넘길 최소 컨텍스트. status만 소비한다. */
function responseErrorContext(status: number): FetchContext & { response: FetchResponse<unknown> } {
  return { request: '/v1/users/me', options: {}, response: { status } as FetchResponse<unknown> } as FetchContext & {
    response: FetchResponse<unknown>
  }
}

describe('useAdminApi', () => {
  beforeEach(() => {
    createMock.mockReset()
    createMock.mockReturnValue(vi.fn() as unknown as typeof $fetch)
    authMock.clearSession.mockReset()
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
    const postHeaders = new Headers()
    onRequest({ request: '/v1/admin/orders', options: { method: 'POST', headers: postHeaders } } as FetchContext)
    expect(postHeaders.get('Authorization')).toBeNull()
    expect(postHeaders.get('X-XSRF-TOKEN')).toBe('xsrf-value')
    const getHeaders = new Headers()
    onRequest({ request: '/v1/admin/me', options: { headers: getHeaders } } as FetchContext)
    expect(getHeaders.get('X-XSRF-TOKEN')).toBeNull()
  })

  it('401 → 관리자 상태만 해제 + /admin/login 이동(사용자 auth 스토어 미호출)', async () => {
    const options = capturedOptions()
    const onResponseError = options.onResponseError
    if (typeof onResponseError !== 'function') throw new Error('onResponseError 미정의')
    await onResponseError(responseErrorContext(401))
    expect(authMock.clearSession).toHaveBeenCalledTimes(1)
    expect(userAuthMock.clearSession).not.toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/admin/login')
  })

  it('403 → 세션 유지(호출부 처리)', async () => {
    const options = capturedOptions()
    const onResponseError = options.onResponseError
    if (typeof onResponseError !== 'function') throw new Error('onResponseError 미정의')
    await onResponseError(responseErrorContext(403))
    expect(authMock.clearSession).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
