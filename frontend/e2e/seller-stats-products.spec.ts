import { test, expect } from '@playwright/test'

/**
 * 셀러 상품 통계(Track 90-E-3·D-200) E2E. 데모 셀러(env NUXT_SELLER_DEMO_*·seller02·실 API)로 /seller/stats/products를 열어 품절 카드·상위/하위·미판매·
 * 재고 회전 표를 waitForResponse 응답과 대조한다(실데이터라 하드코딩 금지). 데모 버튼이 없으면(env 미주입) skip.
 */
interface ProductStatsResponse {
  periodDays: number
  topProducts: { productKey?: string; productName: string }[]
  bottomProducts: { productKey?: string; productName: string }[]
  unsoldProducts: { productKey: string; productName: string }[]
  stockTurnover: { productKey: string; productName: string; depletionDays?: number }[]
  soldOutOptionCount: number
  saleOptionCount: number
}

/** 화면 포맷 재현(seller-product-stats-view formatDepletionDays). */
function depletion(days: number | undefined): string {
  if (days === undefined) return '판매 없음'
  if (days === 0) return '재고 없음'
  return `${days.toLocaleString('ko-KR')}일`
}

const PRODUCTS_URL = /\/api\/v1\/seller\/stats\/products\?/

test.describe('셀러 상품 통계(90-E-3)', () => {
  test('데모 셀러 → 사이드바 통계 3항목 활성 → 품절 카드(현재 시점·재고 링크)·상위/하위·미판매·재고 회전 = 응답 · 프리셋 7일 → from/to 재요청·URL', async ({ page }) => {
    await page.goto('/seller/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('seller-demo-login')
    test.skip((await demoButton.count()) === 0, 'NUXT_SELLER_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    await demoButton.click()
    await page.waitForURL(/\/seller$/)
    await page.setViewportSize({ width: 1440, height: 900 })

    const sidebar = page.getByTestId('seller-sidebar')
    await expect(sidebar.locator('.v-list-item--disabled')).toHaveCount(0)
    await expect(sidebar.locator('a[href="/seller/stats/products"]')).toHaveCount(1)
    const statsResponse = page.waitForResponse((response) => PRODUCTS_URL.test(response.url()) && response.status() === 200)
    await sidebar.locator('a[href="/seller/stats/products"]').click()
    await page.waitForURL(/\/seller\/stats\/products/)
    await expect(page.getByTestId('seller-product-stats')).toBeVisible()
    const stats = (await (await statsResponse).json()) as ProductStatsResponse

    await expect(page.getByTestId('seller-stats-tab-/seller/stats/products')).toHaveClass(/v-tab--selected/)
    await expect(page.getByTestId('seller-stats-tabs').locator('.v-tab--disabled, .v-btn--disabled')).toHaveCount(0)
    // 비교·단위 선택 없음
    await expect(page.getByTestId('period-unit')).toHaveCount(0)
    await expect(page.getByTestId('period-compare')).toHaveCount(0)

    // 품절 카드 = 응답(현재 시점 표기)·재고 화면 링크
    const soldOut = page.getByTestId('soldout-card-link')
    await expect(soldOut).toHaveAttribute('href', '/seller/products/inventory')
    await expect(soldOut.getByTestId('seller-stat-card-value')).toHaveText(`${stats.soldOutOptionCount.toLocaleString('ko-KR')}개`)
    await expect(soldOut).toContainText('현재 시점')

    // 상위/하위: linkable = productKey 있는 행 · 첫 행 이름 = 응답
    for (const [testid, rows] of [['product-top', stats.topProducts], ['product-bottom', stats.bottomProducts]] as const) {
      const linkable = rows.filter((row) => row.productKey).length
      await expect(page.getByTestId(`${testid}-row-linkable`)).toHaveCount(linkable)
      await expect(page.getByTestId(`${testid}-row`)).toHaveCount(rows.length - linkable)
      await expect(page.getByTestId(`${testid}-empty`)).toHaveCount(rows.length === 0 ? 1 : 0)
      if (rows.length > 0) await expect(page.getByTestId(`${testid}-name`).first()).toHaveText(rows[0]!.productName)
    }

    // 미판매·재고 회전: 행 수 = 응답 · 소진 예상 표기 = 응답 포맷
    await expect(page.getByTestId('product-unsold-row')).toHaveCount(stats.unsoldProducts.length)
    await expect(page.getByTestId('product-unsold-empty')).toHaveCount(stats.unsoldProducts.length === 0 ? 1 : 0)
    await expect(page.getByTestId('product-turnover-row')).toHaveCount(stats.stockTurnover.length)
    await expect(page.getByTestId('product-turnover-depletion')).toHaveText(stats.stockTurnover.map((row) => depletion(row.depletionDays)))
    await expect(page.getByTestId('product-turnover-period')).toContainText(`${stats.periodDays}일 기준`)

    // 프리셋 7일 → 재요청 from/to = 7일·URL preset=7d
    const weekResponse = page.waitForResponse((response) => PRODUCTS_URL.test(response.url()) && response.status() === 200)
    await page.getByTestId('period-preset-7d').click()
    const url = new URL((await weekResponse).url())
    const from = new Date(url.searchParams.get('from')!)
    const to = new Date(url.searchParams.get('to')!)
    expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(6)
    await expect(page).toHaveURL(/preset=7d/)
    await expect(page.getByTestId('products-error')).toHaveCount(0)
  })
})
