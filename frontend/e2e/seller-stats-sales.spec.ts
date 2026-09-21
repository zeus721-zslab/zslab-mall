import { test, expect } from '@playwright/test'

/**
 * 셀러 매출 통계(Track 90-E-1·D-200·FE-52) E2E. 데모 셀러(env NUXT_SELLER_DEMO_*·seller02·실 API)로 /seller/stats/sales를 열어 요약 카드 값을
 * waitForResponse 응답과 대조하고(실데이터라 하드코딩 금지·seller-dashboard ② 동형), 축 전환이 breakdown 재요청·URL을 바꾸는지, CSV export가 200인지 본다.
 * 데모 버튼이 없으면(env 미주입) skip.
 */
interface SalesStatsResponse {
  summary: { revenue: number; refund: number; netRevenue: number; orderCount: number; itemQuantity: number; avgOrderValue: number }
  compareSummary?: unknown
  trend: { revenue: number; refund: number; orderCount: number }[]
}

interface BreakdownResponse {
  axis: string
  rows: { key?: string; name?: string }[]
}

/** 화면 포맷 재현(layers/seller/app/lib/format formatWon·formatCount와 동일 식). */
const won = (value: number): string => `${value.toLocaleString('ko-KR')}원`
const count = (value: number, unit: string): string => `${value.toLocaleString('ko-KR')}${unit}`

const STATS_URL = /\/api\/v1\/seller\/stats\/sales(\?|$)/
const BREAKDOWN_URL = /\/api\/v1\/seller\/stats\/sales\/breakdown\?/
const EXPORT_URL = /\/api\/v1\/seller\/stats\/sales\/export\?/

test.describe('셀러 매출 통계(90-E-1)', () => {
  test('데모 셀러 → 사이드바 통계 매출 활성(상품 비활성) · 요약 6 = 응답 · 차트 · 분해 행 = 응답 · 정산 링크 · 축 전환(옵션 → breakdown axis=OPTION·URL) · CSV export 200 text/csv', async ({ page }) => {
    await page.goto('/seller/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('seller-demo-login')
    test.skip((await demoButton.count()) === 0, 'NUXT_SELLER_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    await demoButton.click()
    await page.waitForURL(/\/seller$/)
    await page.setViewportSize({ width: 1440, height: 900 })

    // 사이드바: 통계 그룹 매출·주문클레임 링크·상품 비활성 → 매출 클릭으로 진입
    const sidebar = page.getByTestId('seller-sidebar')
    await expect(sidebar.locator('a[href="/seller/stats/sales"]')).toHaveCount(1)
    await expect(sidebar.locator('.v-list-item--disabled')).toHaveCount(1)
    const statsResponse = page.waitForResponse((response) => STATS_URL.test(response.url()) && response.status() === 200)
    const breakdownResponse = page.waitForResponse((response) => BREAKDOWN_URL.test(response.url()) && response.status() === 200)
    await sidebar.locator('a[href="/seller/stats/sales"]').click()
    await page.waitForURL(/\/seller\/stats\/sales/)
    await expect(page.getByTestId('seller-sales-stats')).toBeVisible()
    const stats = (await (await statsResponse).json()) as SalesStatsResponse
    const breakdown = (await (await breakdownResponse).json()) as BreakdownResponse

    // 통계 탭: 매출 선택·나머지 비활성
    await expect(page.getByTestId('seller-stats-tab-/seller/stats/sales')).toHaveClass(/v-tab--selected/)
    await expect(page.getByTestId('seller-stats-tabs').locator('.v-tab--disabled, .v-btn--disabled')).toHaveCount(1)

    // 요약 6: 카드 값 = 응답 숫자를 화면 포맷으로 변환한 문자열과 정확 일치(셀러 파라미터 없이 리졸버가 식별한 셀러의 값)
    const summary = page.getByTestId('sales-summary')
    await expect(summary.getByTestId('sales-card-revenue').getByTestId('seller-stat-card-value')).toHaveText(won(stats.summary.revenue))
    await expect(summary.getByTestId('sales-card-refund').getByTestId('seller-stat-card-value')).toHaveText(won(stats.summary.refund))
    await expect(summary.getByTestId('sales-card-netRevenue').getByTestId('seller-stat-card-value')).toHaveText(won(stats.summary.netRevenue))
    await expect(summary.getByTestId('sales-card-orderCount').getByTestId('seller-stat-card-value')).toHaveText(count(stats.summary.orderCount, '건'))
    await expect(summary.getByTestId('sales-card-avgOrderValue').getByTestId('seller-stat-card-value')).toHaveText(won(stats.summary.avgOrderValue))
    await expect(summary.getByTestId('sales-card-itemQuantity').getByTestId('seller-stat-card-value')).toHaveText(count(stats.summary.itemQuantity, '개'))
    // 기본 compare=NONE → 배지 전부 "—"
    await expect(summary.getByTestId('sales-card-rate')).toHaveText(Array(6).fill('—'))

    // 정산 안내 링크 · 차트 SVG · 빈 상태 = trend 전부 0일 때만
    await expect(page.getByTestId('sales-settlement-link')).toHaveAttribute('href', '/seller/settlements')
    await expect(page.getByTestId('sales-chart').locator('svg.apexcharts-svg')).toBeVisible()
    const trendAllZero = stats.trend.every((row) => row.revenue === 0 && row.refund === 0 && row.orderCount === 0)
    await expect(page.getByTestId('sales-chart-empty')).toHaveCount(trendAllZero ? 1 : 0)

    // 분해(기본 PRODUCT): 행 수 = 응답 rows · key 있는 행은 상품 상세 링크 행 · 첫 행 이름 = 응답 name
    const linkableRows = breakdown.rows.filter((row) => row.key).length
    await expect(page.getByTestId('breakdown-row-linkable')).toHaveCount(linkableRows)
    await expect(page.getByTestId('breakdown-row')).toHaveCount(breakdown.rows.length - linkableRows)
    await expect(page.getByTestId('breakdown-empty')).toHaveCount(breakdown.rows.length === 0 ? 1 : 0)
    if (breakdown.rows.length > 0) {
      await expect(page.getByTestId('breakdown-name').first()).toHaveText(breakdown.rows[0]!.name ?? '(삭제된 상품)')
    }

    // 축 전환: 옵션 탭 → breakdown axis=OPTION 재요청·URL axis=OPTION·sales는 재요청하지 않음
    let salesCalls = 0
    page.on('request', (request) => { if (STATS_URL.test(request.url()) && request.method() === 'GET') salesCalls += 1 })
    const optionResponse = page.waitForResponse((response) => BREAKDOWN_URL.test(response.url()) && response.url().includes('axis=OPTION') && response.status() === 200)
    await page.getByTestId('breakdown-axis-OPTION').click()
    const option = (await (await optionResponse).json()) as BreakdownResponse
    await expect(page).toHaveURL(/axis=OPTION/)
    await expect(page.getByTestId('breakdown-option-notice')).toBeVisible()
    await expect(page.getByTestId('breakdown-row')).toHaveCount(option.rows.length)
    await expect(page.getByTestId('breakdown-row-linkable')).toHaveCount(0)
    expect(salesCalls).toBe(0)

    // CSV: export 요청 200·text/csv·같은 축(OPTION)·Content-Disposition 한글 파일명
    const exportResponse = page.waitForResponse((response) => EXPORT_URL.test(response.url()))
    await page.getByTestId('sales-csv').click()
    const exported = await exportResponse
    expect(exported.status()).toBe(200)
    expect(exported.url()).toContain('axis=OPTION')
    expect(exported.headers()['content-type']).toContain('text/csv')
    expect(exported.headers()['content-disposition']).toContain("filename*=UTF-8''%EB%A7%A4%EC%B6%9C%ED%86%B5%EA%B3%84_%EC%98%B5%EC%85%98_")
    await expect(page.getByTestId('sales-error')).toHaveCount(0)
  })
})
