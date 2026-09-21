import { test, expect } from '@playwright/test'

/**
 * 셀러 주문·클레임 통계(Track 90-E-2·D-200) E2E. 데모 셀러(env NUXT_SELLER_DEMO_*·seller02·실 API)로 /seller/stats/orders를 열어 퍼널·소요시간·요약·분포·
 * 상품별 값을 waitForResponse 응답과 대조한다(실데이터라 하드코딩 금지·seller-stats-sales 동형). 데모 버튼이 없으면(env 미주입) skip.
 */
interface OrderStatsResponse {
  funnel: { paidItems: number; shippedItems: number; deliveredItems: number }
  leadTime: { paidToShipped?: { medianHours: number; count: number }; shippedToDelivered?: { medianHours: number; count: number } }
  claimSummary: { claimCount: number; claimRate: number; refundAmount: number; refundRate: number }
  claimTrend: { claimCount: number; refundAmount: number; refundCount: number }[]
  claimByType: { count: number }[]
  claimByReason: { count: number }[]
  claimByProduct: { productKey?: string; productName: string }[]
}

const won = (value: number): string => `${value.toLocaleString('ko-KR')}원`
const count = (value: number): string => `${value.toLocaleString('ko-KR')}건`
const percent = (value: number): string => `${value.toFixed(2)}%`

/** 화면 포맷 재현(~/lib/stats-view formatHours). */
function hours(value: number): string {
  if (value < 24) return `${value.toFixed(1)}시간`
  let days = Math.floor(value / 24)
  let remainder = Math.round(value - days * 24)
  if (remainder === 24) {
    days += 1
    remainder = 0
  }
  return remainder === 0 ? `${days}일` : `${days}일 ${remainder}시간`
}

const ORDERS_URL = /\/api\/v1\/seller\/stats\/orders\?/

test.describe('셀러 주문·클레임 통계(90-E-2)', () => {
  test('데모 셀러 → 사이드바 주문클레임 활성 → 퍼널 3단계·소요시간 2·요약 4·분포·상품별 = 응답 · 단위 전환(WEEK) 재요청·URL', async ({ page }) => {
    await page.goto('/seller/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('seller-demo-login')
    test.skip((await demoButton.count()) === 0, 'NUXT_SELLER_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    await demoButton.click()
    await page.waitForURL(/\/seller$/)
    await page.setViewportSize({ width: 1440, height: 900 })

    const sidebar = page.getByTestId('seller-sidebar')
    await expect(sidebar.locator('a[href="/seller/stats/orders"]')).toHaveCount(1)
    await expect(sidebar.locator('.v-list-item--disabled')).toHaveCount(1)
    const statsResponse = page.waitForResponse((response) => ORDERS_URL.test(response.url()) && response.status() === 200)
    await sidebar.locator('a[href="/seller/stats/orders"]').click()
    await page.waitForURL(/\/seller\/stats\/orders/)
    await expect(page.getByTestId('seller-order-stats')).toBeVisible()
    const stats = (await (await statsResponse).json()) as OrderStatsResponse

    await expect(page.getByTestId('seller-stats-tab-/seller/stats/orders')).toHaveClass(/v-tab--selected/)

    // 퍼널: 결제 0이면 빈 문구, 아니면 3단계 건수 = 응답
    const { paidItems, shippedItems, deliveredItems } = stats.funnel
    if (paidItems === 0) {
      await expect(page.getByTestId('funnel-empty')).toBeVisible()
    } else {
      await expect(page.getByTestId('funnel-stage-count')).toHaveText([paidItems, shippedItems, deliveredItems].map(count))
    }

    // 소요시간 2: 표본 있으면 중앙값 포맷·없으면 데이터 없음
    for (const [key, metric] of [['paidToShipped', stats.leadTime.paidToShipped], ['shippedToDelivered', stats.leadTime.shippedToDelivered]] as const) {
      await expect(page.getByTestId(`lead-time-card-${key}`).getByTestId('seller-stat-card-value')).toHaveText(metric ? hours(metric.medianHours) : '데이터 없음')
    }

    // 요약 4 = 응답 포맷(compare=NONE → 배지 —)
    const summary = page.getByTestId('claim-summary')
    await expect(summary.getByTestId('claim-card-claimCount').getByTestId('seller-stat-card-value')).toHaveText(count(stats.claimSummary.claimCount))
    await expect(summary.getByTestId('claim-card-claimRate').getByTestId('seller-stat-card-value')).toHaveText(percent(stats.claimSummary.claimRate))
    await expect(summary.getByTestId('claim-card-refundAmount').getByTestId('seller-stat-card-value')).toHaveText(won(stats.claimSummary.refundAmount))
    await expect(summary.getByTestId('claim-card-refundRate').getByTestId('seller-stat-card-value')).toHaveText(percent(stats.claimSummary.refundRate))
    await expect(summary.getByTestId('claim-card-rate')).toHaveText(Array(4).fill('—'))

    // 추이 차트·빈 상태 = claimTrend 전부 0일 때만
    await expect(page.getByTestId('claim-trend-chart').locator('svg.apexcharts-svg')).toBeVisible()
    const trendAllZero = stats.claimTrend.every((row) => row.claimCount === 0 && row.refundAmount === 0 && row.refundCount === 0)
    await expect(page.getByTestId('claim-trend-empty')).toHaveCount(trendAllZero ? 1 : 0)

    // 분포: 행 수 = 응답 · 합 0이면 도넛 빈 문구
    await expect(page.getByTestId('claim-by-type-row')).toHaveCount(stats.claimByType.length)
    await expect(page.getByTestId('claim-by-reason-row')).toHaveCount(stats.claimByReason.length)
    await expect(page.getByTestId('claim-by-type-empty')).toHaveCount(stats.claimByType.length === 0 ? 1 : 0)

    // 상품별: linkable = productKey 있는 행 · 첫 행 이름 = 응답
    const linkable = stats.claimByProduct.filter((row) => row.productKey).length
    await expect(page.getByTestId('claim-product-row-linkable')).toHaveCount(linkable)
    await expect(page.getByTestId('claim-product-row')).toHaveCount(stats.claimByProduct.length - linkable)
    await expect(page.getByTestId('claim-by-product-empty')).toHaveCount(stats.claimByProduct.length === 0 ? 1 : 0)
    if (stats.claimByProduct.length > 0) {
      await expect(page.getByTestId('claim-product-name').first()).toHaveText(stats.claimByProduct[0]!.productName)
    }

    // 단위 전환 WEEK → 재요청 unit=WEEK·URL 반영
    const weekResponse = page.waitForResponse((response) => ORDERS_URL.test(response.url()) && response.url().includes('unit=WEEK') && response.status() === 200)
    await page.getByTestId('period-unit').click()
    await page.getByRole('option', { name: '주' }).click()
    expect((await weekResponse).status()).toBe(200)
    await expect(page).toHaveURL(/unit=WEEK/)
    await expect(page.getByTestId('orders-error')).toHaveCount(0)
  })
})
