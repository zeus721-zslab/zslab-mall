import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerOrderStatsPage from '#layers/seller/app/pages/seller/stats/orders.vue'
import { presetPeriod } from '~/lib/stats-period'
import type { SellerOrderStatsResponse } from '#layers/seller/app/types/seller-order-stats'

/**
 * 셀러 주문·클레임 통계 페이지(Track 90-E-2). URL query → 요청 파라미터·기간 검증(역전·365일 초과면 미요청)·퍼널/소요시간/요약/분포/상품별 렌더·
 * 빈 상태·에러/재시도·상품 행 이동만 본다. API·토스트는 mock(실 네트워크 없음)·차트는 stub(apexcharts는 window 의존)·라우터는 실제(90-E-1 spec 트랩).
 */
const { apiMock, navigateToMock } = vi.hoisted(() => ({ apiMock: vi.fn(), navigateToMock: vi.fn() }))
mockNuxtImport('useSellerApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('definePageMeta', () => () => {})

const PAGE_PATH = '/seller/stats/orders'
const ORDERS_PATH = '/v1/seller/stats/orders'

function statsResponse(overrides: Partial<SellerOrderStatsResponse> = {}): SellerOrderStatsResponse {
  return {
    funnel: { paidItems: 4, shippedItems: 3, deliveredItems: 2 },
    leadTime: { paidToShipped: { avgHours: 32, medianHours: 24, count: 3 }, shippedToDelivered: { avgHours: 72, medianHours: 72, count: 2 } },
    claimSummary: { claimCount: 3, claimRate: 75, refundAmount: 15_000, refundRate: 35.71, refundCount: 2, paidItemCount: 4 },
    claimTrend: [
      { bucketKey: '2026-03-01', bucketLabel: '2026-03-01', claimCount: 0, claimRate: 0, refundAmount: 0, refundRate: 0, refundCount: 0 },
      { bucketKey: '2026-03-02', bucketLabel: '2026-03-02', claimCount: 1, claimRate: 50, refundAmount: 10_000, refundRate: 20, refundCount: 1 },
    ],
    claimByType: [{ type: 'CANCEL', count: 2, share: 66.67 }, { type: 'RETURN', count: 1, share: 33.33 }],
    claimByReason: [{ reasonCode: 'PRODUCT_DEFECT', count: 3, share: 100 }],
    claimByProduct: [{ productKey: 'prd_A2', productName: '통계상품A2', count: 2, share: 66.67 }, { productName: '삭제상품', count: 1, share: 33.33 }],
    ...overrides,
  }
}

function callsTo(path: string) {
  return apiMock.mock.calls.filter((call) => call[0] === path)
}

/** 셀러 미들웨어의 로그인 리다이렉트(navigateTo 문자열)도 mock에 잡히므로 상품 상세 이동(객체 인자)만 고른다. */
function productNavigations() {
  return navigateToMock.mock.calls.map((call) => call[0]).filter((target) => typeof target === 'object' && target !== null)
}

const mounted: { unmount: () => void }[] = []
async function mountPage(query = '') {
  const wrapper = await mountSuspended(SellerOrderStatsPage, { route: `${PAGE_PATH}${query}`, global: { plugins: [createVuetify()], stubs: { SellerChart: true } } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('셀러 주문·클레임 통계 페이지', () => {
  beforeEach(() => {
    apiMock.mockReset()
    navigateToMock.mockReset()
    apiMock.mockImplementation((path: string) => (path === ORDERS_PATH ? Promise.resolve(statsResponse()) : Promise.reject(new Error(`unexpected ${path}`))))
  })

  afterEach(() => {
    mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('진입(기본 30d·DAY·NONE) → orders 1회(오늘 기준 기간·셀러 파라미터 없음) · 퍼널 3단계·소요시간 중앙값·요약 4·분포·상품별 2행 · 탭 주문클레임 활성', async () => {
    const wrapper = await mountPage()
    const expected = presetPeriod('30d', new Date())
    expect(callsTo(ORDERS_PATH)).toHaveLength(1)
    expect(callsTo(ORDERS_PATH)[0]?.[1]).toEqual({ params: { from: expected.from, to: expected.to, unit: 'DAY', compare: 'NONE' } })
    expect(wrapper.findAll('[data-testid="funnel-stage-count"]').map((node) => node.text())).toEqual(['4건', '3건', '2건'])
    expect(wrapper.findAll('[data-testid="funnel-stage-reach"]').map((node) => node.text())).toEqual(['100.0%', '75.0%', '50.0%'])
    expect(wrapper.find('[data-testid="lead-time-card-paidToShipped"]').text()).toContain('1일')
    expect(wrapper.find('[data-testid="lead-time-card-shippedToDelivered"] [data-testid="seller-stat-card-value"]').text()).toBe('3일')
    expect(wrapper.find('[data-testid="claim-card-claimCount"]').text()).toContain('3건')
    expect(wrapper.find('[data-testid="claim-card-refundRate"]').text()).toContain('35.71%')
    expect(wrapper.findAll('[data-testid="claim-card-rate"]').every((chip) => chip.text() === '—')).toBe(true)
    expect(wrapper.findAll('[data-testid="claim-by-type-row"]').map((row) => row.text())).toEqual(['취소2건66.67%', '반품1건33.33%'])
    expect(wrapper.findAll('[data-testid="claim-by-reason-row"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="claim-product-row-linkable"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="claim-product-row"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="seller-stats-tab-/seller/stats/orders"]').classes()).toContain('v-tab--selected')
    expect(wrapper.find('[data-testid="seller-stats-tab-pending-2"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="period-hint"]').text()).toContain('365일')
  })

  it('기간 오류: 역전·366일 → 미요청·문구 / URL unit=MONTH&compare=PREVIOUS → 파라미터 반영·비교 데이터 0 안내', async () => {
    const inverted = await mountPage('?preset=custom&from=2026-03-11&to=2026-03-10')
    expect(apiMock).not.toHaveBeenCalled()
    expect(inverted.find('[data-testid="period-error"]').text()).toContain('시작일이 종료일보다')
    const tooLong = await mountPage('?preset=custom&from=2025-03-31&to=2026-03-31')
    expect(apiMock).not.toHaveBeenCalled()
    expect(tooLong.find('[data-testid="period-error"]').text()).toContain('365일')

    const compared = await mountPage('?unit=MONTH&compare=PREVIOUS')
    expect(callsTo(ORDERS_PATH)[0]?.[1]).toEqual({ params: { ...presetPeriod('30d', new Date()), unit: 'MONTH', compare: 'PREVIOUS' } })
    expect(compared.find('[data-testid="orders-compare-unavailable"]').exists()).toBe(true)
  })

  it('빈 상태: 퍼널 0·소요시간 표본 0·추이 전부 0·분포/상품별 빈 배열 → 각 빈 문구', async () => {
    apiMock.mockImplementation(() => Promise.resolve(statsResponse({
      funnel: { paidItems: 0, shippedItems: 0, deliveredItems: 0 },
      leadTime: {},
      claimSummary: { claimCount: 0, claimRate: 0, refundAmount: 0, refundRate: 0, refundCount: 0, paidItemCount: 0 },
      claimTrend: [{ bucketKey: '2026-03-01', bucketLabel: '2026-03-01', claimCount: 0, claimRate: 0, refundAmount: 0, refundRate: 0, refundCount: 0 }],
      claimByType: [],
      claimByReason: [],
      claimByProduct: [],
    })))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="funnel-empty"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="seller-stat-card-value"]').filter((node) => node.text() === '데이터 없음')).toHaveLength(2)
    expect(wrapper.find('[data-testid="claim-trend-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="claim-by-type-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="claim-by-reason-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="claim-by-product-empty"]').exists()).toBe(true)
  })

  it('에러: 실패 → 알림·다시 시도 → 재호출 후 복구', async () => {
    let fails = true
    apiMock.mockImplementation(() => (fails ? Promise.reject({ status: 500, data: { code: 'INTERNAL_ERROR' } }) : Promise.resolve(statsResponse())))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="orders-error"]').exists()).toBe(true)
    fails = false
    await wrapper.find('[data-testid="orders-retry"]').trigger('click')
    await flushPromises()
    expect(callsTo(ORDERS_PATH)).toHaveLength(2)
    expect(wrapper.find('[data-testid="orders-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="funnel-stage-count"]')).toHaveLength(3)
  })

  it('상품별 행 클릭 → /seller/products/{key}?back=현재 통계 URL · key 없는 행은 이동 없음', async () => {
    const wrapper = await mountPage('?preset=7d')
    await wrapper.find('[data-testid="claim-product-row"]').trigger('click')
    expect(productNavigations()).toHaveLength(0)
    await wrapper.find('[data-testid="claim-product-row-linkable"]').trigger('click')
    expect(productNavigations()).toEqual([{ path: '/seller/products/prd_A2', query: { back: '/seller/stats/orders?preset=7d' } }])
  })
})
