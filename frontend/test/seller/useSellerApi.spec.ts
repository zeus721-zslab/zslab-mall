import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { FetchContext, FetchResponse } from 'ofetch'
import { useSellerApi } from '#layers/seller/app/composables/useSellerApi'

// $fetch.create에 넘긴 옵션(baseURL·onRequest·onResponseError)을 붙잡아 훅을 직접 호출·검증한다(실 네트워크 없음).
// D-190 분기: 401은 seller_token만 비우고 셀러 로그인으로, 403 SELLER_SUSPENDED는 로그아웃 없이 정지 안내 플래그만, 그 외 403은 호출부 처리.
const { sellerAuthMock, userAuthMock, navigateToMock } = vi.hoisted(() => ({
  sellerAuthMock: { token: 'seller-token', logout: vi.fn(), markSuspended: vi.fn() },
  userAuthMock: { token: 'user-token', logout: vi.fn() },
  navigateToMock: vi.fn(),
}))

vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
mockNuxtImport('useAuthStore', () => () => userAuthMock)
mockNuxtImport('navigateTo', () => navigateToMock)

type CreateOptions = Parameters<typeof $fetch.create>[0]
const createMock = vi.fn<(options: CreateOptions) => typeof $fetch>()

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
    sellerAuthMock.logout.mockReset()
    sellerAuthMock.markSuspended.mockReset()
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

  it('onRequest → Authorization: Bearer <seller_token> 주입', () => {
    const options = capturedOptions()
    const headers = new Headers()
    const onRequest = options.onRequest
    if (typeof onRequest !== 'function') throw new Error('onRequest 미정의')
    onRequest({ request: '/v1/users/me', options: { headers } } as FetchContext)
    expect(headers.get('Authorization')).toBe('Bearer seller-token')
  })

  it('401 → seller_token만 logout + /seller/login 이동(사용자 auth 스토어 logout 미호출·정지 플래그 미변경)', async () => {
    await fireResponseError(401)
    expect(sellerAuthMock.logout).toHaveBeenCalledTimes(1)
    expect(userAuthMock.logout).not.toHaveBeenCalled()
    expect(navigateToMock).toHaveBeenCalledWith('/seller/login')
    expect(sellerAuthMock.markSuspended).not.toHaveBeenCalled()
  })

  it('403 SELLER_SUSPENDED → 정지 안내 플래그만 켜고 세션 유지(logout·이동 없음)', async () => {
    await fireResponseError(403, { code: 'SELLER_SUSPENDED', status: 403, detail: '정지 상태의 셀러는 해당 작업을 수행할 수 없습니다.' })
    expect(sellerAuthMock.markSuspended).toHaveBeenCalledTimes(1)
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('403 다른 코드(FORBIDDEN)·본문 없음 → 세션 유지·플래그 미변경(호출부 처리)', async () => {
    await fireResponseError(403, { code: 'FORBIDDEN' })
    await fireResponseError(403)
    expect(sellerAuthMock.markSuspended).not.toHaveBeenCalled()
    expect(sellerAuthMock.logout).not.toHaveBeenCalled()
    expect(navigateToMock).not.toHaveBeenCalled()
  })
})
