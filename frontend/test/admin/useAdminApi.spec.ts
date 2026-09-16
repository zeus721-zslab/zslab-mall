import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { FetchContext, FetchResponse } from 'ofetch'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

// $fetch.create에 넘긴 옵션(baseURL·onRequest·onResponseError)을 붙잡아 훅을 직접 호출·검증한다(실 네트워크 없음).
// FE-22d: Bearer·401 처리는 관리자 세션(admin_token) 스토어만 다루고 사용자 auth 스토어는 건드리지 않는다(401 격리).
const { authMock, userAuthMock, navigateToMock } = vi.hoisted(() => ({
  authMock: { token: 'admin-token', logout: vi.fn() },
  userAuthMock: { token: 'user-token', logout: vi.fn() },
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/admin/app/stores/adminAuth', () => ({ useAdminAuthStore: () => authMock }))
mockNuxtImport('useAuthStore', () => () => userAuthMock)
mockNuxtImport('navigateTo', () => navigateToMock)

type CreateOptions = Parameters<typeof $fetch.create>[0]
const createMock = vi.fn<(options: CreateOptions) => typeof $fetch>()

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
    authMock.logout.mockReset()
    userAuthMock.logout.mockReset()
    navigateToMock.mockReset()
    vi.stubGlobal('$fetch', Object.assign(vi.fn(), { create: createMock }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('baseURL은 브라우저 상대경로(/api)', () => {
    expect(capturedOptions().baseURL).toBe('/api')
  })

  it('onRequest → Authorization: Bearer <token> 주입', () => {
    const options = capturedOptions()
    const headers = new Headers()
    const onRequest = options.onRequest
    if (typeof onRequest !== 'function') throw new Error('onRequest 미정의')
    onRequest({ request: '/v1/users/me', options: { headers } } as FetchContext)
    expect(headers.get('Authorization')).toBe('Bearer admin-token')
  })

  it('401 → admin_token만 logout + /admin/login 이동(사용자 auth 스토어 logout 미호출)', async () => {
    const options = capturedOptions()
    const onResponseError = options.onResponseError
    if (typeof onResponseError !== 'function') throw new Error('onResponseError 미정의')
    await onResponseError(responseErrorContext(401))
    expect(authMock.logout).toHaveBeenCalledTimes(1)
    expect(userAuthMock.logout).not.toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/admin/login')
  })

  it('403 → 세션 유지(호출부 처리)', async () => {
    const options = capturedOptions()
    const onResponseError = options.onResponseError
    if (typeof onResponseError !== 'function') throw new Error('onResponseError 미정의')
    await onResponseError(responseErrorContext(403))
    expect(authMock.logout).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
