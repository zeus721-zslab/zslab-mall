import { test, expect, type Page } from '@playwright/test'

/**
 * 관리자 매출 통계(FE-34) 스모크. 로그인은 데모 버튼(NUXT_ADMIN_DEMO_* 주입 환경·미주입 시 skip), 통계 API는 실 BE(D-181)를 호출한다.
 * 진입(기본 30일·카테고리) → 기간 프리셋·단위·비교 변경(URL 반영) → 축 전환(셀러) → 드릴다운(상품·브레드크럼) → 복귀 → 기간 역전 검증.
 * CSV는 blob 다운로드 이벤트를 잡아 파일명만 확인한다. 데이터 유무와 무관하게 성립하는 단언만 둔다.
 */
async function loginByDemo(page: Page): Promise<void> {
  await page.goto('/admin/login')
  await page.waitForLoadState('networkidle')
  const demoButton = page.getByTestId('admin-demo-login')
  test.skip((await demoButton.count()) === 0, 'NUXT_ADMIN_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
  await demoButton.click()
  await page.waitForURL(/\/admin$/)
}

function waitForStats(page: Page, path: string, predicate: (url: string) => boolean = () => true) {
  return page.waitForResponse((response) => response.url().includes(path) && predicate(response.url()) && response.status() === 200)
}

test.describe('관리자 매출 통계 (FE-34)', () => {
  test('① 진입 → 기간·단위·비교 변경 → 축 전환 → 드릴다운 → 복귀 → 역전 검증 → CSV', async ({ page }) => {
    await loginByDemo(page)

    const firstSales = waitForStats(page, '/api/v1/admin/stats/sales?')
    const firstBreakdown = waitForStats(page, '/api/v1/admin/stats/sales/breakdown?')
    await page.goto('/admin/stats/sales')
    await firstSales
    await firstBreakdown

    await expect(page.getByTestId('admin-sales-stats')).toBeVisible()
    await expect(page.getByTestId('admin-placeholder')).toHaveCount(0)
    await expect(page.getByTestId('admin-stat-card')).toHaveCount(6)
    await expect(page.getByTestId('sales-card-revenue').getByTestId('admin-stat-card-value')).toContainText('원')
    await expect(page.getByTestId('sales-card-avgItemsPerOrder').getByTestId('admin-stat-card-value')).toContainText('개')
    // 비교 없음 → 배지 전부 "—"
    for (const text of await page.getByTestId('sales-card-rate').allTextContents()) expect(text.trim()).toBe('—')
    await expect(page.getByTestId('sales-chart').locator('svg.apexcharts-svg')).toBeVisible()
    await expect(page.getByTestId('breakdown-category-notice')).toContainText('현재 카테고리')

    // 기간 프리셋 7일 + 단위 주 + 비교 직전 → URL 반영·재조회
    const weekSales = waitForStats(page, '/api/v1/admin/stats/sales?', (url) => url.includes('unit=WEEK') && url.includes('compare=PREVIOUS'))
    await page.getByTestId('period-preset-7d').click()
    await page.getByTestId('period-unit').click()
    await page.getByRole('option', { name: '주' }).click()
    await page.getByTestId('period-compare').click()
    await page.getByRole('option', { name: '직전 기간' }).click()
    await weekSales
    await expect(page).toHaveURL(/preset=7d/)
    await expect(page).toHaveURL(/unit=WEEK/)
    await expect(page).toHaveURL(/compare=PREVIOUS/)
    // 비교 열 2개 노출(비교 매출·증감률)
    await expect(page.getByTestId('breakdown-sort-compareRevenue')).toBeVisible()
    await expect(page.getByTestId('breakdown-sort-rate')).toBeVisible()
    for (const text of await page.getByTestId('sales-card-rate').allTextContents()) expect(text.trim()).toMatch(/^([+-]?\d+\.\d%|—)$/)

    // 축 전환: 셀러 → breakdown만 재조회(axis=SELLER)·카테고리 안내 사라짐
    const sellerBreakdown = waitForStats(page, '/api/v1/admin/stats/sales/breakdown?', (url) => url.includes('axis=SELLER'))
    await page.getByTestId('breakdown-axis-SELLER').click()
    await sellerBreakdown
    await expect(page).toHaveURL(/axis=SELLER/)
    await expect(page.getByTestId('breakdown-category-notice')).toHaveCount(0)

    // 드릴다운: 드릴 가능한 행이 있으면 클릭 → parentKey 요청·브레드크럼 → 전체로 복귀
    const drillable = page.getByTestId('breakdown-row-drillable')
    if ((await drillable.count()) > 0) {
      const sellerName = (await drillable.first().getByTestId('breakdown-name').textContent())?.trim() ?? ''
      const drillBreakdown = waitForStats(page, '/api/v1/admin/stats/sales/breakdown?', (url) => url.includes('parentKey='))
      await drillable.first().click()
      await drillBreakdown
      await expect(page).toHaveURL(/parent=/)
      await expect(page.getByTestId('breakdown-breadcrumb-parent')).toContainText(sellerName)
      await expect(page.getByTestId('breakdown-row-drillable')).toHaveCount(0)
      const upBreakdown = waitForStats(page, '/api/v1/admin/stats/sales/breakdown?', (url) => !url.includes('parentKey='))
      await page.getByTestId('breakdown-drill-up').click()
      await upBreakdown
      await expect(page).not.toHaveURL(/parent=/)
      await expect(page.getByTestId('breakdown-breadcrumb')).toHaveCount(0)
    } else {
      await expect(page.getByTestId('breakdown-empty')).toContainText('데이터 없음')
    }

    // 기간 역전: 시작일 직접 입력 → custom 전환·재조회 / 종료일 < 시작일이면 요청 없이 인라인 메시지
    const customSales = waitForStats(page, '/api/v1/admin/stats/sales?', (url) => url.includes('from=2026-09-10'))
    await page.getByTestId('period-from').locator('input').fill('2026-09-10')
    await customSales
    await expect(page).toHaveURL(/preset=custom/)
    let requestsAfterInvert = 0
    page.on('request', (request) => { if (request.url().includes('/api/v1/admin/stats/sales')) requestsAfterInvert += 1 })
    await page.getByTestId('period-to').locator('input').fill('2026-09-01')
    await expect(page.getByTestId('period-inverted')).toBeVisible()
    await expect(page.getByTestId('sales-csv')).toBeDisabled()
    await page.waitForTimeout(500)
    expect(requestsAfterInvert).toBe(0)

    // 역전 해소 → CSV blob 다운로드(파일명은 Content-Disposition에서)
    const restored = waitForStats(page, '/api/v1/admin/stats/sales/breakdown?', (url) => url.includes('from=2026-09-01') && url.includes('to=2026-09-10'))
    await page.getByTestId('period-from').locator('input').fill('2026-09-01')
    await page.getByTestId('period-to').locator('input').fill('2026-09-10')
    await restored
    // Playwright Chromium은 blob: URL 다운로드의 suggestedFilename을 "download"로 보고한다(앱의 a.download는 한글 파일명·실측) →
    // 다운로드 이벤트 발생 + 토스트에 찍힌 파일명(Content-Disposition filename* 추출값)으로 확인한다.
    const download = page.waitForEvent('download')
    await page.getByTestId('sales-csv').click()
    await download
    await expect(page.locator('[data-sonner-toast]').first()).toContainText('매출통계_셀러_2026-09-01_2026-09-10.csv')
  })
})
