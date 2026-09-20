import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { mockSellerMe } from './helpers/seller-mock'

/**
 * 셀러 대시보드(Track 90-B-3·D-192) E2E. ①은 loginAs(SELLER·SELLER_E2E_* 주입·미주입 시 skip) + 대시보드 API mock(결정적 값), ②는 데모 셀러(env NUXT_SELLER_DEMO_*·
 * 실 API)로 응답과 화면을 대조한다(FE-50: 데모 계정은 실데이터라 "데이터 0" 하드코딩 대신 waitForResponse 응답 기준·admin-dashboard ① 동형).
 */
const DAILY = Array.from({ length: 30 }, (_, index) => {
  const date = new Date(2026, 7, 22 + index)
  const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  return { date: key, orderCount: index === 29 ? 2 : 0, revenue: index === 29 ? 77000 : 0 }
})

const DASHBOARD = {
  period: { from: '2026-08-22', to: '2026-09-20' },
  summary: { revenue: 232000, refund: 32000, netRevenue: 200000, orderCount: 6 },
  pending: { deliveryReady: 2, claimRequested: 0, lowStock: 1, settlementPending: 1 },
  dailyTrend: DAILY,
  recentOrderItems: [
    { orderItemId: 'oit_E2E1', orderNo: '20260917-E2E1', productName: 'E2E 반찬통', quantity: 1, totalPrice: 32000, itemStatus: 'PAID', paidAt: '2026-09-17T17:32:23+09:00' },
    { orderItemId: 'oit_E2E2', orderNo: '20260916-E2E2', productName: 'E2E 주전자', optionLabel: '블랙', quantity: 2, totalPrice: 90000, itemStatus: 'SHIPPING', paidAt: '2026-09-16T10:00:00+09:00' },
  ],
  recentClaims: [
    { claimPublicId: 'clm_E2E1', type: 'RETURN', status: 'REQUESTED', orderNo: '20260913-E2E3', requestedAt: '2026-09-18T03:52:00+09:00' },
  ],
  topProducts: [{ productPublicId: 'prd_E2E1', productName: 'E2E 냄비 세트', revenue: 89000, quantity: 1 }],
}

async function mockDashboard(page: Page): Promise<URLSearchParams[]> {
  const queries: URLSearchParams[] = []
  await mockSellerMe(page)
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/dashboard'), (route) => {
    const query = new URL(route.request().url()).searchParams
    queries.push(query)
    const from = query.get('from') ?? DASHBOARD.period.from
    const to = query.get('to') ?? DASHBOARD.period.to
    return route.fulfill({ json: { ...DASHBOARD, period: { from, to } } })
  })
  return queries
}

/** ②가 대조하는 응답 필드(BE SellerDashboardResponse 중 화면 값으로 쓰이는 부분만). */
interface DemoDashboardResponse {
  summary: { revenue: number; refund: number; netRevenue: number; orderCount: number }
  pending: { deliveryReady: number; claimRequested: number; lowStock: number; settlementPending: number }
  dailyTrend: { date: string; orderCount: number; revenue: number }[]
  recentOrderItems: unknown[]
  recentClaims: unknown[]
  topProducts: unknown[]
}

/** 화면 포맷 재현(layers/seller/app/lib/format formatWon·formatCount와 동일 식). */
const won = (value: number): string => `${value.toLocaleString('ko-KR')}원`
const count = (value: number): string => `${value.toLocaleString('ko-KR')}건`

/** 목록 카드(SellerDashboardListCard): 응답 길이 0이면 `-empty`만, 아니면 `-row` 수 = 길이·`-empty` 없음. */
async function expectListMatches(page: Page, testId: string, length: number): Promise<void> {
  await expect(page.getByTestId(`${testId}-row`)).toHaveCount(length)
  await expect(page.getByTestId(`${testId}-empty`)).toHaveCount(length === 0 ? 1 : 0)
}

test.describe('셀러 대시보드(90-B-3)', () => {
  test('① 진입 → 상단바 상호·상태 · 요약 4(품목 축 캡션·주문 건수 의미) · 대기 4(배송 대기만 링크) · 차트 2 · 최근 품목 링크·클레임 링크 없음 · 기간 프리셋 → API from/to · 93일 직접 입력 → 클라이언트 오류·API 미호출', async ({ page }) => {
    const queries = await mockDashboard(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller')

    await expect(page.getByTestId('seller-company-name')).toHaveText('E2E 셀러샵')
    await expect(page.getByTestId('seller-status-chip')).toHaveText('정상')
    await expect(page.getByTestId('seller-suspended-notice')).toHaveCount(0)

    // 요약 4: 값 + 캡션(품목 축·주문 건수 의미)
    const summary = page.getByTestId('dashboard-summary')
    await expect(summary.getByTestId('dashboard-card-revenue')).toContainText('232,000원')
    await expect(summary.getByTestId('dashboard-card-revenue')).toContainText('내 품목 합계')
    await expect(summary.getByTestId('dashboard-card-refund')).toContainText('32,000원')
    await expect(summary.getByTestId('dashboard-card-netRevenue')).toContainText('200,000원')
    await expect(summary.getByTestId('dashboard-card-orderCount')).toContainText('6건')
    await expect(summary.getByTestId('dashboard-card-orderCount')).toContainText('품목 수와 다를 수 있음')

    // 대기 4: 배송 대기만 링크(품목 목록 status=PAID), 나머지 카운트·힌트
    const pending = page.getByTestId('dashboard-pending')
    await expect(pending.getByTestId('dashboard-pending-count')).toHaveText(['2건', '0건', '1건', '1건'])
    await expect(page.getByTestId('dashboard-pending-deliveryReady')).toHaveAttribute('href', '/seller/orders?status=PAID')
    expect(await page.getByTestId('dashboard-pending-settlementPending').evaluate((el) => el.tagName)).toBe('DIV')
    await expect(page.getByTestId('dashboard-pending-settlementPending')).toContainText('확정 전 정산 건수')

    // 차트 2(apexcharts svg)
    await expect(page.getByTestId('dashboard-chart-revenue').locator('svg.apexcharts-svg')).toBeVisible()
    await expect(page.getByTestId('dashboard-chart-orders').locator('svg.apexcharts-svg')).toBeVisible()

    // 최근 품목: 행 링크 → 품목 상세(back=대시보드) · 클레임: 링크 없음(90-D) · 상위 상품
    const recentRows = page.getByTestId('dashboard-recent-order-items-row')
    await expect(recentRows).toHaveCount(2)
    await expect(recentRows.first()).toHaveAttribute('href', `/seller/orders/oit_E2E1?back=${encodeURIComponent('/seller')}`)
    await expect(recentRows.nth(1)).toContainText('E2E 주전자 (블랙) · 2개')
    const claimRows = page.getByTestId('dashboard-recent-claims-row')
    await expect(claimRows).toHaveCount(1)
    expect(await claimRows.first().getAttribute('href')).toBeNull()
    await expect(page.getByTestId('dashboard-recent-claims-all')).toHaveCount(0)
    await expect(page.getByTestId('dashboard-top-products-row')).toContainText('E2E 냄비 세트')

    // 기간: 초기 호출은 기본 30일(from·to 동봉) → 프리셋 7일 클릭 → 새 호출 from/to = 7일
    expect(queries).toHaveLength(1)
    expect(queries[0]?.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    await page.getByTestId('dashboard-preset-7').click()
    await expect.poll(() => queries.length).toBe(2)
    const from = new Date(queries[1]!.get('from')!)
    const to = new Date(queries[1]!.get('to')!)
    expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(6)

    // 93일 직접 입력(시작일을 종료일 − 92일로) → 클라이언트 검증 문구·API 미호출
    const toValue = queries[1]!.get('to')!
    const tooEarly = new Date(to.getTime() - 92 * 86_400_000)
    const tooEarlyValue = `${tooEarly.getFullYear()}-${String(tooEarly.getMonth() + 1).padStart(2, '0')}-${String(tooEarly.getDate()).padStart(2, '0')}`
    await page.getByTestId('dashboard-from').locator('input').fill(tooEarlyValue)
    await expect(page.getByTestId('dashboard-period-error')).toContainText('최대 92일')
    expect(queries).toHaveLength(2)
    expect(toValue).toBe(queries[1]!.get('to'))
  })

  test('② 데모 셀러(실 API) → 응답 대조: 요약 4·대기 4 값 일치 · 최근 목록 3 행 수 또는 빈 상태 · 차트 빈 상태 = dailyTrend 전부 0', async ({ page }) => {
    await page.goto('/seller/login')
    await page.waitForLoadState('networkidle')
    const demoButton = page.getByTestId('seller-demo-login')
    test.skip((await demoButton.count()) === 0, 'NUXT_SELLER_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
    // 대시보드 응답은 데모 로그인 직후 /seller 진입에서 발생하므로 클릭 전에 대기를 등록한다(seller-shell ⑤·admin-dashboard ① 동형)
    const dashboardResponse = page.waitForResponse((response) => response.url().includes('/api/v1/seller/dashboard') && response.status() === 200)
    await demoButton.click()
    await page.waitForURL(/\/seller$/)
    const dashboard = (await (await dashboardResponse).json()) as DemoDashboardResponse

    // 요약 4: 카드 값 = 응답 숫자를 화면과 같은 포맷(formatWon·formatCount)으로 변환한 문자열과 정확 일치
    const summary = page.getByTestId('dashboard-summary')
    await expect(summary.getByTestId('dashboard-card-revenue').getByTestId('seller-stat-card-value')).toHaveText(won(dashboard.summary.revenue))
    await expect(summary.getByTestId('dashboard-card-refund').getByTestId('seller-stat-card-value')).toHaveText(won(dashboard.summary.refund))
    await expect(summary.getByTestId('dashboard-card-netRevenue').getByTestId('seller-stat-card-value')).toHaveText(won(dashboard.summary.netRevenue))
    await expect(summary.getByTestId('dashboard-card-orderCount').getByTestId('seller-stat-card-value')).toHaveText(count(dashboard.summary.orderCount))

    // 대기 4: 순서 = 배송 대기 · 클레임 · 재고 임박 · 정산 예정(SellerDashboardPending 타일 순서)
    const { deliveryReady, claimRequested, lowStock, settlementPending } = dashboard.pending
    await expect(page.getByTestId('dashboard-pending').getByTestId('dashboard-pending-count')).toHaveText([deliveryReady, claimRequested, lowStock, settlementPending].map(count))

    // 최근 목록 3: 응답 배열 길이 = 행 수, 0이면 빈 상태 문구
    await expectListMatches(page, 'dashboard-recent-order-items', dashboard.recentOrderItems.length)
    await expectListMatches(page, 'dashboard-recent-claims', dashboard.recentClaims.length)
    await expectListMatches(page, 'dashboard-top-products', dashboard.topProducts.length)

    // 차트 2: dailyTrend가 전부 0일 때만 "데이터 없음"(isAllZero) · SVG는 항상 렌더
    const revenueAllZero = dashboard.dailyTrend.every((row) => row.revenue === 0)
    const orderCountAllZero = dashboard.dailyTrend.every((row) => row.orderCount === 0)
    await expect(page.getByTestId('dashboard-chart-revenue-empty')).toHaveCount(revenueAllZero ? 1 : 0)
    await expect(page.getByTestId('dashboard-chart-orders-empty')).toHaveCount(orderCountAllZero ? 1 : 0)
    await expect(page.getByTestId('dashboard-chart-revenue').locator('svg.apexcharts-svg')).toBeVisible()
    await expect(page.getByTestId('dashboard-error')).toHaveCount(0)
  })
})
