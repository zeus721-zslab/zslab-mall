import { test, expect, type Page } from '@playwright/test'

/**
 * 관리자 주문·클레임 통계 / 회원 통계(FE-35) 스모크. 로그인은 데모 버튼(NUXT_ADMIN_DEMO_* 주입 환경·미주입 시 skip), 통계 API는 실 BE(D-182)를 호출한다.
 * 데이터 유무와 무관하게 성립하는 단언만 둔다(퍼널 4단계·소요시간 카드 3·차트 SVG·분포 카드·요약 카드 6·등급 3행·상위 회원 표). 탭 간 이동 1케이스 포함.
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

test.describe('관리자 주문·클레임 / 회원 통계 (FE-35)', () => {
  test('① 주문·클레임 통계: 진입 → 퍼널·소요시간·추이·분포 렌더 → 단위·비교 변경(URL) → 역전 검증 → 탭으로 회원 통계 이동', async ({ page }) => {
    await loginByDemo(page)

    const first = waitForStats(page, '/api/v1/admin/stats/orders?')
    await page.goto('/admin/stats/orders')
    await first

    await expect(page.getByTestId('admin-order-stats')).toBeVisible()
    await expect(page.getByTestId('admin-placeholder')).toHaveCount(0)
    await expect(page.getByTestId('funnel-notice')).toContainText('기간 내 결제된 주문')
    // 퍼널 4단계(데이터 없으면 빈 문구)
    const stages = page.locator('[data-testid^="funnel-stage-"][data-testid$="Items"]')
    if ((await page.getByTestId('funnel-empty').count()) === 0) {
      await expect(stages).toHaveCount(4)
      await expect(page.getByTestId('funnel-stage-paidItems').getByTestId('funnel-stage-reach')).toHaveText('100.0%')
      await expect(page.getByTestId('funnel-stage-drop')).toHaveCount(3)
    }
    await expect(page.getByTestId('funnel-cancelled')).toContainText('취소')
    await expect(page.locator('[data-testid^="lead-time-card-"]')).toHaveCount(3)
    await expect(page.getByTestId('lead-time-median')).toHaveCount(3)
    await expect(page.getByTestId('claim-summary').getByTestId('admin-stat-card')).toHaveCount(4)
    for (const text of await page.getByTestId('claim-card-rate').allTextContents()) expect(text.trim()).toBe('—')
    await expect(page.getByTestId('claim-trend-chart').locator('svg.apexcharts-svg')).toBeVisible()
    await expect(page.getByTestId('claim-by-type')).toBeVisible()
    await expect(page.getByTestId('claim-by-reason')).toBeVisible()
    if ((await page.getByTestId('claim-by-type-empty').count()) === 0) {
      await expect(page.getByTestId('claim-by-type').locator('svg.apexcharts-svg')).toBeVisible()
      await expect(page.getByTestId('claim-by-type-row').first()).toBeVisible()
    }

    // 단위 월 + 비교 직전 → URL 반영·재조회
    const monthly = waitForStats(page, '/api/v1/admin/stats/orders?', (url) => url.includes('unit=MONTH') && url.includes('compare=PREVIOUS'))
    await page.getByTestId('period-preset-3m').click()
    await page.getByTestId('period-unit').click()
    await page.getByRole('option', { name: '월' }).click()
    await page.getByTestId('period-compare').click()
    await page.getByRole('option', { name: '직전 기간' }).click()
    await monthly
    await expect(page).toHaveURL(/preset=3m/)
    await expect(page).toHaveURL(/unit=MONTH/)
    await expect(page).toHaveURL(/compare=PREVIOUS/)
    for (const text of await page.getByTestId('claim-card-rate').allTextContents()) expect(text.trim()).toMatch(/^([+-]?\d+\.\d%|—)$/)

    // 기간 역전 → 요청 없이 인라인 메시지
    const custom = waitForStats(page, '/api/v1/admin/stats/orders?', (url) => url.includes('from=2026-09-10'))
    await page.getByTestId('period-from').locator('input').fill('2026-09-10')
    await custom
    let requestsAfterInvert = 0
    page.on('request', (request) => { if (request.url().includes('/api/v1/admin/stats/orders')) requestsAfterInvert += 1 })
    await page.getByTestId('period-to').locator('input').fill('2026-09-01')
    await expect(page.getByTestId('period-inverted')).toBeVisible()
    await page.waitForTimeout(500)
    expect(requestsAfterInvert).toBe(0)

    // 탭 이동: 회원 통계
    const members = waitForStats(page, '/api/v1/admin/stats/members?')
    await page.getByTestId('stats-tab-members').click()
    await members
    await expect(page).toHaveURL(/\/admin\/stats\/members$/)
    await expect(page.getByTestId('admin-member-stats')).toBeVisible()
  })

  test('② 회원 통계: 진입 → 요약 카드 6·가입 추이 차트·등급 3행·분리·상위 회원 표 → 탭으로 매출 통계 이동', async ({ page }) => {
    await loginByDemo(page)

    const first = waitForStats(page, '/api/v1/admin/stats/members?')
    await page.goto('/admin/stats/members')
    await first

    await expect(page.getByTestId('admin-member-stats')).toBeVisible()
    await expect(page.getByTestId('admin-placeholder')).toHaveCount(0)
    await expect(page.getByTestId('member-summary').getByTestId('admin-stat-card')).toHaveCount(6)
    await expect(page.getByTestId('member-card-activeTotal').getByTestId('admin-stat-card-value')).toContainText('명')
    await expect(page.getByTestId('member-card-repurchaseRate').getByTestId('admin-stat-card-value')).toContainText('%')
    for (const text of await page.getByTestId('member-card-rate').allTextContents()) expect(text.trim()).toBe('—')
    await expect(page.getByTestId('signup-chart').locator('svg.apexcharts-svg')).toBeVisible()
    await expect(page.getByTestId('grade-distribution-notice')).toContainText('현재 등급')
    await expect(page.getByTestId('grade-distribution-row')).toHaveCount(3)
    await expect(page.getByTestId('buyer-split')).toBeVisible()
    await expect(page.getByTestId('top-buyers-table')).toBeVisible()
    const navigable = page.getByTestId('top-buyers-row-navigable')
    if ((await navigable.count()) > 0) {
      await expect(navigable.first().getByTestId('top-buyers-name')).not.toBeEmpty()
    } else {
      await expect(page.getByTestId('top-buyers-empty')).toContainText('데이터 없음')
    }

    // 탭 이동: 매출 통계
    const sales = waitForStats(page, '/api/v1/admin/stats/sales?')
    await page.getByTestId('stats-tab-sales').click()
    await sales
    await expect(page).toHaveURL(/\/admin\/stats\/sales$/)
    await expect(page.getByTestId('admin-sales-stats')).toBeVisible()
  })
})
