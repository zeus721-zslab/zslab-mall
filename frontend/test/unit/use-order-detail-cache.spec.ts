import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useOrderDetail } from '~/composables/useOrders'
import { useClaimDetail } from '~/composables/useClaim'

// W1: 주문·클레임 상세는 Nuxt 기본 getCachedData(hydration 중에만 SSR payload 재사용·클라이언트 진입은 재조회)를 써야 한다.
// `() => undefined`로 덮으면 hydration 중에도 payload를 버려 첫 렌더가 스켈레톤 → hydration mismatch가 재발한다.
const { useFetchMock } = vi.hoisted(() => ({ useFetchMock: vi.fn() }))
mockNuxtImport('useFetch', () => useFetchMock)
mockNuxtImport('useBuyerApi', () => () => vi.fn())

function lastFetchOptions(): Record<string, string | object> {
  return useFetchMock.mock.calls[0]?.[1] as Record<string, string | object>
}

describe('상세 조회 캐시 옵션(W1)', () => {
  beforeEach(() => {
    useFetchMock.mockReset()
  })

  it('useOrderDetail: getCachedData를 덮어쓰지 않는다(기본 동작 사용) · 주문별 key', () => {
    useOrderDetail('ord_TEST')
    expect(useFetchMock.mock.calls[0]?.[0]).toBe('/v1/orders/ord_TEST')
    expect(lastFetchOptions().key).toBe('order-detail:ord_TEST')
    expect('getCachedData' in lastFetchOptions()).toBe(false)
  })

  it('useClaimDetail: getCachedData를 덮어쓰지 않는다(기본 동작 사용) · 클레임별 key', () => {
    useClaimDetail('clm_TEST')
    expect(useFetchMock.mock.calls[0]?.[0]).toBe('/v1/claims/clm_TEST')
    expect(lastFetchOptions().key).toBe('claim-detail:clm_TEST')
    expect('getCachedData' in lastFetchOptions()).toBe(false)
  })
})
