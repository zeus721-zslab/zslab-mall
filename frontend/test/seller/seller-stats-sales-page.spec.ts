import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerSalesStatsPage from '#layers/seller/app/pages/seller/stats/sales.vue'
import { presetPeriod } from '~/lib/stats-period'
import type { SellerSalesBreakdownResponse, SellerSalesStatsResponse } from '#layers/seller/app/types/seller-stats'

/**
 * 셀러 매출 통계 페이지(Track 90-E-1·FE-52). URL query → 요청 파라미터·기간 검증(역전·365일 초과면 미요청)·축 전환(router.replace)·빈 상태·에러/재시도·
 * CSV(export raw 호출·파일명 토스트)·상품 행 이동만 본다. API·라우터·토스트는 mock(실 네트워크 없음)·차트는 stub(apexcharts는 window 의존).
 */
const { apiMock, rawMock, toastMock, navigateToMock } = vi.hoisted(() => {
  const apiMock = vi.fn() as ReturnType<typeof vi.fn> & { raw: ReturnType<typeof vi.fn> }
  const rawMock = vi.fn()
  apiMock.raw = rawMock
  return {
    apiMock,
    rawMock,
    toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
    navigateToMock: vi.fn(),
  }
})
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))
mockNuxtImport('useSellerApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('definePageMeta', () => () => {})

const PAGE_PATH = '/seller/stats/sales'

const SALES_PATH = '/v1/seller/stats/sales'
const BREAKDOWN_PATH = `${SALES_PATH}/breakdown`
const EXPORT_PATH = `${SALES_PATH}/export`

function statsResponse(overrides: Partial<SellerSalesStatsResponse> = {}): SellerSalesStatsResponse {
  return {
    summary: { revenue: 48_000, refund: 10_000, netRevenue: 38_000, orderCount: 4, itemQuantity: 6, avgOrderValue: 12_000, avgItemsPerOrder: 1.5 },
    trend: [
      { bucketKey: '2026-03-01', bucketLabel: '2026-03-01', revenue: 6_000, refund: 0, netRevenue: 6_000, orderCount: 1 },
      { bucketKey: '2026-03-02', bucketLabel: '2026-03-02', revenue: 0, refund: 0, netRevenue: 0, orderCount: 0 },
    ],
    ...overrides,
  }
}

function breakdownResponse(overrides: Partial<SellerSalesBreakdownResponse> = {}): SellerSalesBreakdownResponse {
  return {
    axis: 'PRODUCT',
    totalRevenue: 48_000,
    rows: [
      { key: 'prd_A2', name: '통계상품A2', revenue: 26_000, share: 54.17, orderCount: 2, quantity: 3 },
      { key: 'prd_A', name: '통계상품A', revenue: 22_000, share: 45.83, orderCount: 3, quantity: 3 },
    ],
    ...overrides,
  }
}

function mockApi(stats: () => Promise<SellerSalesStatsResponse>, breakdown: () => Promise<SellerSalesBreakdownResponse>): void {
  apiMock.mockImplementation((path: string) => (path === SALES_PATH ? stats() : path === BREAKDOWN_PATH ? breakdown() : Promise.reject(new Error(`unexpected ${path}`))))
}

function callsTo(path: string) {
  return apiMock.mock.calls.filter((call) => call[0] === path)
}

/** 실제 라우터로 마운트하면 셀러 미들웨어의 로그인 리다이렉트(navigateTo 문자열)도 mock에 잡히므로 상품 상세 이동(객체 인자)만 고른다. */
function productNavigations() {
  return navigateToMock.mock.calls.map((call) => call[0]).filter((target) => typeof target === 'object' && target !== null)
}

// 라우터는 실제(mountSuspended route 옵션)를 써서 route.query watch·router.replace 반영까지 본다(useRouter mock은 nuxt test-utils 셋업을 깨뜨림·실측).
// 라우터가 테스트 파일 안에서 공유되므로 마운트한 페이지는 테스트 끝에 언마운트한다(살아 있으면 다음 테스트의 라우트 변경에 watch가 반응해 요청 수가 섞인다·실측).
const mounted: { unmount: () => void }[] = []
async function mountPage(query = '') {
  const wrapper = await mountSuspended(SellerSalesStatsPage, { route: `${PAGE_PATH}${query}`, global: { plugins: [createVuetify()], stubs: { SellerChart: true } } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('셀러 매출 통계 페이지', () => {
  beforeEach(() => {
    apiMock.mockReset()
    rawMock.mockReset()
    navigateToMock.mockReset()
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    mockApi(() => Promise.resolve(statsResponse()), () => Promise.resolve(breakdownResponse()))
  })

  afterEach(() => {
    mounted.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.unstubAllGlobals()
  })

  it('진입(기본 30d·DAY·NONE·PRODUCT) → sales·breakdown 각 1회(오늘 기준 기간·셀러 파라미터 없음) · 카드 값·행 2·정산 링크·탭 매출 활성', async () => {
    const wrapper = await mountPage()
    const expected = presetPeriod('30d', new Date())
    expect(callsTo(SALES_PATH)).toHaveLength(1)
    expect(callsTo(SALES_PATH)[0]?.[1]).toEqual({ params: { from: expected.from, to: expected.to, unit: 'DAY', compare: 'NONE' } })
    expect(callsTo(BREAKDOWN_PATH)[0]?.[1]).toEqual({ params: { from: expected.from, to: expected.to, compare: 'NONE', axis: 'PRODUCT' } })
    expect(wrapper.find('[data-testid="sales-card-revenue"]').text()).toContain('48,000원')
    expect(wrapper.find('[data-testid="sales-card-itemQuantity"]').text()).toContain('6개')
    expect(wrapper.find('[data-testid="sales-card-avgOrderValue"]').text()).toContain('12,000원')
    expect(wrapper.findAll('[data-testid="sales-card-rate"]').every((chip) => chip.text() === '—')).toBe(true)
    expect(wrapper.findAll('[data-testid="breakdown-row-linkable"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="sales-settlement-link"]').attributes('href')).toBe('/seller/settlements')
    expect(wrapper.find('[data-testid="seller-stats-tab-/seller/stats/sales"]').classes()).toContain('v-tab--selected')
    expect(wrapper.find('[data-testid="seller-stats-tab-/seller/stats/products"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-testid="period-hint"]').text()).toContain('365일')
  })

  it('기간 오류: 역전 → 미요청·오류 문구 / 366일 → 미요청·365일 문구 / custom 한쪽 누락 → 기본 프리셋으로 조회', async () => {
    const inverted = await mountPage('?preset=custom&from=2026-03-11&to=2026-03-10')
    expect(apiMock).not.toHaveBeenCalled()
    expect(inverted.find('[data-testid="period-error"]').text()).toContain('시작일이 종료일보다')
    expect(inverted.find('[data-testid="sales-csv"]').attributes('disabled')).toBeDefined()

    const tooLong = await mountPage('?preset=custom&from=2025-03-31&to=2026-03-31')
    expect(apiMock).not.toHaveBeenCalled()
    expect(tooLong.find('[data-testid="period-error"]').text()).toContain('365일')

    await mountPage('?preset=custom&from=2026-03-01')
    expect(callsTo(SALES_PATH)[0]?.[1]).toEqual({ params: { ...presetPeriod('30d', new Date()), unit: 'DAY', compare: 'NONE' } })
  })

  it('축 전환: 옵션 탭 클릭 → URL axis=OPTION → breakdown만 재조회(sales 1회 유지) · URL axis=CATEGORY 진입 → 카테고리 안내 · 비카테고리 행은 이동 불가', async () => {
    const wrapper = await mountPage()
    // v-tabs 선택은 jsdom에서 슬라이드 그룹(ResizeObserver)이 동작하지 않아 클릭이 모델을 바꾸지 않는다 → 탭 컴포넌트의 모델 이벤트로 축 전환을 흉내낸다
    expect(wrapper.find('[data-testid="breakdown-axis-OPTION"]').exists()).toBe(true)
    // 페이지의 v-tabs 2개(통계 탭·축 탭) 중 축 탭은 두 번째
    const axisTabs = wrapper.findAllComponents({ name: 'VTabs' })[1]
    expect(axisTabs?.find('[data-testid="breakdown-axis-OPTION"]').exists()).toBe(true)
    axisTabs?.vm.$emit('update:modelValue', 'OPTION')
    // router.replace → route.query 반영은 마이크로태스크만으로 끝나지 않아(vue-router 내비게이션·실측 수십 ms) 폴링으로 기다린다
    await vi.waitFor(() => expect(useRouter().currentRoute.value.query).toEqual({ axis: 'OPTION' }))
    await flushPromises()
    expect(callsTo(BREAKDOWN_PATH)).toHaveLength(2)
    expect(callsTo(BREAKDOWN_PATH)[1]?.[1]).toEqual({ params: { ...presetPeriod('30d', new Date()), compare: 'NONE', axis: 'OPTION' } })
    expect(callsTo(SALES_PATH)).toHaveLength(1)
    wrapper.unmount()
    apiMock.mockClear()

    mockApi(
      () => Promise.resolve(statsResponse({ compareSummary: statsResponse().summary, compareTrend: statsResponse().trend })),
      () => Promise.resolve(breakdownResponse({ axis: 'CATEGORY', rows: [{ key: '9', name: '카테고리9', revenue: 48_000, share: 100, orderCount: 4, quantity: 6, compareRevenue: 24_000 }] })),
    )
    const category = await mountPage('?axis=CATEGORY&unit=MONTH&compare=PREVIOUS')
    expect(callsTo(SALES_PATH)[0]?.[1]).toEqual({ params: { ...presetPeriod('30d', new Date()), unit: 'MONTH', compare: 'PREVIOUS' } })
    expect(callsTo(BREAKDOWN_PATH)[0]?.[1]).toEqual({ params: { ...presetPeriod('30d', new Date()), compare: 'PREVIOUS', axis: 'CATEGORY' } })
    expect(category.find('[data-testid="breakdown-category-notice"]').exists()).toBe(true)
    expect(category.findAll('[data-testid="breakdown-row"]')).toHaveLength(1)
    expect(category.findAll('[data-testid="breakdown-row-linkable"]')).toHaveLength(0)
    expect(category.find('[data-testid="breakdown-rate"]').text()).toBe('+100.0%')
    expect(category.find('[data-testid="sales-compare-unavailable"]').exists()).toBe(false)
    await category.find('[data-testid="breakdown-row"]').trigger('click')
    expect(productNavigations()).toHaveLength(0)
  })

  it('비교 기간 데이터 0(compareSummary 생략) → 안내 알림 · 배지 —', async () => {
    const wrapper = await mountPage('?compare=YEAR_AGO')
    expect(wrapper.find('[data-testid="sales-compare-unavailable"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="sales-card-rate"]').every((chip) => chip.text() === '—')).toBe(true)
  })

  it('빈 상태: 추이 전부 0·행 0 → 차트 "데이터 없음"·표 "데이터 없음"', async () => {
    mockApi(
      () => Promise.resolve(statsResponse({ summary: { revenue: 0, refund: 0, netRevenue: 0, orderCount: 0, itemQuantity: 0, avgOrderValue: 0, avgItemsPerOrder: 0 }, trend: [{ bucketKey: '2026-03-01', bucketLabel: '2026-03-01', revenue: 0, refund: 0, netRevenue: 0, orderCount: 0 }] })),
      () => Promise.resolve(breakdownResponse({ rows: [] })),
    )
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="sales-chart-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="breakdown-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sales-card-revenue"]').text()).toContain('0원')
  })

  it('에러: sales 실패 → 알림·다시 시도 → 재호출 후 복구 / breakdown 실패 → 표 자리 알림·재시도', async () => {
    let salesFails = true
    let breakdownFails = true
    mockApi(
      () => (salesFails ? Promise.reject({ status: 500, data: { code: 'INTERNAL_ERROR' } }) : Promise.resolve(statsResponse())),
      () => (breakdownFails ? Promise.reject({ status: 500, data: { code: 'INTERNAL_ERROR' } }) : Promise.resolve(breakdownResponse())),
    )
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="sales-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="breakdown-error"]').exists()).toBe(true)
    salesFails = false
    breakdownFails = false
    await wrapper.find('[data-testid="sales-retry"]').trigger('click')
    await wrapper.find('[data-testid="breakdown-retry"]').trigger('click')
    await flushPromises()
    expect(callsTo(SALES_PATH)).toHaveLength(2)
    expect(callsTo(BREAKDOWN_PATH)).toHaveLength(2)
    expect(wrapper.find('[data-testid="sales-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="breakdown-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="breakdown-row-linkable"]')).toHaveLength(2)
  })

  it('CSV: 버튼 → export raw(blob·같은 breakdown 파라미터) → 파일명(filename*) 토스트 · 실패 → danger 토스트', async () => {
    const createObjectURL = vi.fn(() => 'blob:mock')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', Object.assign(Object.create(URL), { createObjectURL, revokeObjectURL }))
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    rawMock.mockResolvedValueOnce({
      _data: new Blob(['x']),
      headers: new Headers({ 'content-disposition': "attachment; filename=\"seller-sales-breakdown-product-2026-03-01_2026-03-31.csv\"; filename*=UTF-8''%EB%A7%A4%EC%B6%9C%ED%86%B5%EA%B3%84_%EC%83%81%ED%92%88_2026-03-01_2026-03-31.csv" }),
    })
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="sales-csv"]').trigger('click')
    await flushPromises()
    expect(rawMock).toHaveBeenCalledTimes(1)
    expect(rawMock.mock.calls[0]?.[0]).toBe(EXPORT_PATH)
    expect(rawMock.mock.calls[0]?.[1]).toEqual({ params: { ...presetPeriod('30d', new Date()), compare: 'NONE', axis: 'PRODUCT' }, responseType: 'blob' })
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(toastMock.success).toHaveBeenCalledWith('매출통계_상품_2026-03-01_2026-03-31.csv 다운로드를 시작했습니다.')

    rawMock.mockRejectedValueOnce({ status: 500, data: { code: 'INTERNAL_ERROR' } })
    await wrapper.find('[data-testid="sales-csv"]').trigger('click')
    await flushPromises()
    expect(toastMock.danger).toHaveBeenCalledTimes(1)
    expect(toastMock.danger.mock.calls[0]?.[0]).toContain('CSV 내보내기 실패')
    clickSpy.mockRestore()
  })

  it('상품 행 클릭 → /seller/products/{key}?back=현재 통계 URL', async () => {
    const wrapper = await mountPage('?preset=7d')
    await wrapper.find('[data-testid="breakdown-row-linkable"]').trigger('click')
    expect(productNavigations()).toEqual([{ path: '/seller/products/prd_A2', query: { back: '/seller/stats/sales?preset=7d' } }])
  })
})
