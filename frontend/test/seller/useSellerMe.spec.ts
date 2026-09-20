import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useSellerMe } from '#layers/seller/app/composables/useSellerMe'
import type { SellerMe } from '#layers/seller/app/types/seller-me'

// Track 90-B-3: GET /seller/me 셸 소비 — 로드 성공 시 공유 상태(useState)에 저장, status=SUSPENDED면 진입 시점에 정지 배너 플래그(markSuspended)를 켠다.
// 실패는 표시만 비우고(error=true) throw하지 않는다(셸 렌더 계속·401은 useSellerApi가 처리).
const { apiMock, sellerAuthMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  sellerAuthMock: { markSuspended: vi.fn() },
}))
vi.mock('#layers/seller/app/stores/sellerAuth', () => ({ useSellerAuthStore: () => sellerAuthMock }))
mockNuxtImport('useSellerApi', () => () => apiMock)

const ACTIVE_ME: SellerMe = {
  sellerPublicId: 'slr_01', companyName: '데모 리빙샵', status: 'ACTIVE', roleCode: 'SELLER_OWNER', pendingSettlementCount: 1, bankAccountRegistered: true,
}

describe('useSellerMe', () => {
  beforeEach(() => {
    apiMock.mockReset()
    sellerAuthMock.markSuspended.mockReset()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    useSellerMe().clear()
  })

  it('ACTIVE → me 저장·markSuspended 미호출·같은 composable 인스턴스가 상태를 공유한다', async () => {
    apiMock.mockResolvedValue(ACTIVE_ME)
    const first = useSellerMe()
    await first.load()
    expect(apiMock).toHaveBeenCalledWith('/v1/seller/me')
    expect(first.me.value?.companyName).toBe('데모 리빙샵')
    expect(first.error.value).toBe(false)
    expect(sellerAuthMock.markSuspended).not.toHaveBeenCalled()
    expect(useSellerMe().me.value?.sellerPublicId).toBe('slr_01')
  })

  it('SUSPENDED → me 저장 + markSuspended 호출(첫 쓰기 거부 전에 배너)', async () => {
    apiMock.mockResolvedValue({ ...ACTIVE_ME, status: 'SUSPENDED' })
    const composable = useSellerMe()
    await composable.load()
    expect(composable.me.value?.status).toBe('SUSPENDED')
    expect(sellerAuthMock.markSuspended).toHaveBeenCalledTimes(1)
  })

  it('실패 → me null·error true·throw 없음(console.warn 1회)', async () => {
    apiMock.mockRejectedValue({ status: 500 })
    const composable = useSellerMe()
    await expect(composable.load()).resolves.toBeUndefined()
    expect(composable.me.value).toBeNull()
    expect(composable.error.value).toBe(true)
    expect(console.warn).toHaveBeenCalledTimes(1)
  })
})
