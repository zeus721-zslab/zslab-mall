import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerProductStatsPage from '#layers/seller/app/pages/seller/stats/products.vue'
import { presetPeriod } from '~/lib/stats-period'
import type { SellerProductStatsResponse } from '#layers/seller/app/types/seller-product-stats'

/**
 * 셀러 상품 통계 페이지(Track 90-E-3). URL query → 요청 파라미터(from·to만)·기간 검증(역전·365일 초과면 미요청)·표 4 렌더·품절 카드(재고 화면 링크)·
 * 빈 상태·에러/재시도·상품 행 이동만 본다. API는 mock·라우터는 실제(90-E-1 spec 트랩)·비교/단위 선택은 숨김.
 */
const { apiMock, navigateToMock } = vi.hoisted(() => ({ apiMock: vi.fn(), navigateToMock: vi.fn() }))
mockNuxtImport('useSellerApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('definePageMeta', () => () => {})

const PAGE_PATH = '/seller/stats/products'
const PRODUCTS_PATH = '/v1/seller/stats/products'

function statsResponse(overrides: Partial<SellerProductStatsResponse> = {}): SellerProductStatsResponse {
  return {
    periodDays: 30,
    topProducts: [
      { productKey: 'prd_A', productName: '통계상품A', revenue: 30_000, orderCount: 2, quantity: 3 },
      { productName: '삭제상품', revenue: 1_000, orderCount: 1, quantity: 1 },
    ],
    bottomProducts: [
      { productName: '삭제상품', revenue: 1_000, orderCount: 1, quantity: 1 },
      { productKey: 'prd_A', productName: '통계상품A', revenue: 30_000, orderCount: 2, quantity: 3 },
    ],
    unsoldProducts: [{ productKey: 'prd_A3', productName: '통계상품A3', basePrice: 10_000 }],
    stockTurnover: [
      { productKey: 'prd_A2', productName: '통계상품A2', inboundQuantity: 0, soldQuantity: 1, availableQuantity: 0, depletionDays: 0 },
      { productKey: 'prd_A', productName: '통계상품A', inboundQuantity: 80, soldQuantity: 3, availableQuantity: 5, depletionDays: 52 },
      { productKey: 'prd_A3', productName: '통계상품A3', inboundQuantity: 20, soldQuantity: 0, availableQuantity: 10 },
    ],
    soldOutOptionCount: 2,
    saleOptionCount: 5,
    ...overrides,
  }
}

function callsTo(path: string) {
  return apiMock.mock.calls.filter((call) => call[0] === path)
}

function productNavigations() {
  return navigateToMock.mock.calls.map((call) => call[0]).filter((target) => typeof target === 'object' && target !== null)
}

const mounted: { unmount: () => void }[] = []
async function mountPage(query = '') {
  const wrapper = await mountSuspended(SellerProductStatsPage, { route: `${PAGE_PATH}${query}`, global: { plugins: [createVuetify()] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('셀러 상품 통계 페이지', () => {
  beforeEach(() => {
    apiMock.mockReset()
    navigateToMock.mockReset()
    apiMock.mockImplementation((path: string) => (path === PRODUCTS_PATH ? Promise.resolve(statsResponse()) : Promise.reject(new Error(`unexpected ${path}`))))
  })

  afterEach(() => {
    mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('진입(기본 30d) → products 1회(from·to만·단위/비교 선택 없음) · 품절 카드(현재 시점·재고 링크) · 상위/하위·미판매·재고 회전 렌더 · 탭 상품 활성', async () => {
    const wrapper = await mountPage()
    const expected = presetPeriod('30d', new Date())
    expect(callsTo(PRODUCTS_PATH)).toHaveLength(1)
    expect(callsTo(PRODUCTS_PATH)[0]?.[1]).toEqual({ params: { from: expected.from, to: expected.to } })
    expect(wrapper.find('[data-testid="period-unit"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="period-compare"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="soldout-card-link"]').attributes('href')).toBe('/seller/products/inventory')
    expect(wrapper.find('[data-testid="soldout-card-link"] [data-testid="seller-stat-card-value"]').text()).toBe('2개')
    expect(wrapper.find('[data-testid="soldout-card-link"]').text()).toContain('현재 시점(기간과 무관)')
    expect(wrapper.findAll('[data-testid="product-top-row-linkable"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="product-top-row"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="product-bottom-name"]').map((node) => node.text())).toEqual(['삭제상품', '통계상품A'])
    expect(wrapper.findAll('[data-testid="product-unsold-row"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="product-turnover-depletion"]').map((node) => node.text())).toEqual(['재고 없음', '52일', '판매 없음'])
    expect(wrapper.find('[data-testid="product-turnover-period"]').text()).toContain('30일 기준')
    expect(wrapper.find('[data-testid="seller-stats-tab-/seller/stats/products"]').classes()).toContain('v-tab--selected')
    expect(wrapper.find('[data-testid="products-all-empty"]').exists()).toBe(false)
  })

  it('기간 오류: 역전·366일 → 미요청·문구 / custom 정상 → from·to 반영', async () => {
    const inverted = await mountPage('?preset=custom&from=2026-03-11&to=2026-03-10')
    expect(apiMock).not.toHaveBeenCalled()
    expect(inverted.find('[data-testid="period-error"]').text()).toContain('시작일이 종료일보다')
    const tooLong = await mountPage('?preset=custom&from=2025-03-31&to=2026-03-31')
    expect(apiMock).not.toHaveBeenCalled()
    expect(tooLong.find('[data-testid="period-error"]').text()).toContain('365일')
    await mountPage('?preset=custom&from=2026-03-01&to=2026-03-31')
    expect(callsTo(PRODUCTS_PATH)[0]?.[1]).toEqual({ params: { from: '2026-03-01', to: '2026-03-31' } })
  })

  it('빈 상태: 표 4 빈 배열·품절 0/0 → 각 빈 문구 + 전체 빈 안내 · 품절 캡션 "판매 중 옵션 없음"', async () => {
    apiMock.mockImplementation(() => Promise.resolve(statsResponse({ topProducts: [], bottomProducts: [], unsoldProducts: [], stockTurnover: [], soldOutOptionCount: 0, saleOptionCount: 0 })))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="products-all-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="product-top-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="product-bottom-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="product-unsold-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="product-turnover-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="soldout-card-link"]').text()).toContain('판매 중 옵션 없음')
  })

  it('에러: 실패 → 알림·다시 시도 → 재호출 후 복구', async () => {
    let fails = true
    apiMock.mockImplementation(() => (fails ? Promise.reject({ status: 500, data: { code: 'INTERNAL_ERROR' } }) : Promise.resolve(statsResponse())))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="products-error"]').exists()).toBe(true)
    fails = false
    await wrapper.find('[data-testid="products-retry"]').trigger('click')
    await flushPromises()
    expect(callsTo(PRODUCTS_PATH)).toHaveLength(2)
    expect(wrapper.find('[data-testid="products-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="product-turnover-row"]')).toHaveLength(3)
  })

  it('상품 행 클릭(상위·미판매·재고 회전) → /seller/products/{key}?back=현재 통계 URL · 삭제 행은 이동 없음', async () => {
    const wrapper = await mountPage('?preset=7d')
    await wrapper.find('[data-testid="product-top-row"]').trigger('click')
    expect(productNavigations()).toHaveLength(0)
    await wrapper.find('[data-testid="product-top-row-linkable"]').trigger('click')
    await wrapper.find('[data-testid="product-unsold-row"]').trigger('click')
    await wrapper.findAll('[data-testid="product-turnover-row"]')[1]!.trigger('click')
    expect(productNavigations()).toEqual([
      { path: '/seller/products/prd_A', query: { back: '/seller/stats/products?preset=7d' } },
      { path: '/seller/products/prd_A3', query: { back: '/seller/stats/products?preset=7d' } },
      { path: '/seller/products/prd_A', query: { back: '/seller/stats/products?preset=7d' } },
    ])
  })
})
