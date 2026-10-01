import { test, expect } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 대시보드(FE-33) 스모크. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 대시보드 API는 실 BE(D-180)를 호출해
 * 요약 카드 6·처리 대기 10·차트 2(apexcharts SVG)·리스트 4가 렌더되는지 확인한다. 데이터 유무와 무관하게 성립하는 단언만 둔다.
 * 클레임 처리 대기 타일(Track 96-4)은 클릭 → 클레임 목록 ?action=FOLLOWUP 복원·필터 select 표시까지 확인한다.
 */
test.describe('관리자 대시보드 (FE-33)', () => {
  test('① 로그인 → /admin: 요약 카드 6·증감 배지·처리 대기 10·차트 2·리스트 4 렌더', async ({ page }) => {
    const dashboardResponse = page.waitForResponse((response) => response.url().includes('/api/v1/admin/dashboard') && response.status() === 200)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin')
    await dashboardResponse

    await expect(page.getByTestId('admin-dashboard')).toBeVisible()
    await expect(page.getByTestId('admin-placeholder')).toHaveCount(0)

    // 요약 카드 6장·값은 '—'가 아니어야(응답 반영) 하고 증감 배지는 %, 또는 비교 불가 '—'
    await expect(page.getByTestId('admin-stat-card')).toHaveCount(6)
    await expect(page.getByTestId('dashboard-card-today-revenue').getByTestId('admin-stat-card-value')).toContainText('원')
    await expect(page.getByTestId('dashboard-card-thisMonth-orders').getByTestId('admin-stat-card-value')).toContainText('건')
    const rates = page.getByTestId('dashboard-card-rate')
    await expect(rates).toHaveCount(6)
    for (const text of await rates.allTextContents()) {
      expect(text.trim()).toMatch(/^([+-]?\d+\.\d%|—)$/)
    }

    // 처리 대기 9칸: 링크 9(정산·클레임·배송·재고 임박=상품 목록 stockFilter=LOW·Track 89-A / 상품·셀러 승인 대기=status=PENDING·Track 96-2 /
    // 클레임 처리 대기=action=FOLLOWUP·Track 96-4 / 장기 배송중=배송 목록 status=SHIPPING·Track 99 D-210 / 불일치=status=OPEN·Track 104-2)
    // Track 106-4: 미답변 문의 칸 추가로 10칸(문의 관리 목록 · 기본 필터 미답변)
    await expect(page.getByTestId('dashboard-pending-count')).toHaveCount(10)
    // FE-101: 인박스 유형이 있는 8칸은 인박스 유형 필터 · 배송 대기·재고 임박은 기존 목록
    await expect(page.getByTestId('dashboard-pending-settlementPending')).toHaveAttribute('href', '/admin/inbox?type=SETTLEMENT_CONFIRM')
    await expect(page.getByTestId('dashboard-pending-claimRequested')).toHaveAttribute('href', '/admin/inbox?type=CLAIM_REQUESTED')
    await expect(page.getByTestId('dashboard-pending-deliveryReady')).toHaveAttribute('href', '/admin/orders?status=PAID')
    await expect(page.getByTestId('dashboard-pending-lowStock')).toHaveAttribute('href', '/admin/products?stockFilter=LOW')
    await expect(page.getByTestId('dashboard-pending-productPending')).toHaveAttribute('href', '/admin/inbox?type=PRODUCT_APPROVAL')
    await expect(page.getByTestId('dashboard-pending-sellerPending')).toHaveAttribute('href', '/admin/inbox?type=SELLER_REVIEW')
    await expect(page.getByTestId('dashboard-pending-claimFollowup')).toHaveAttribute('href', '/admin/inbox?type=CLAIM_FOLLOWUP')
    await expect(page.getByTestId('dashboard-pending-longShipping')).toHaveAttribute('href', '/admin/inbox?type=LONG_SHIPPING')
    await expect(page.getByTestId('dashboard-pending-reconciliationOpen')).toHaveAttribute('href', '/admin/inbox?type=RECONCILIATION_OPEN')
    await expect(page.getByTestId('dashboard-pending-inquiryUnanswered')).toHaveAttribute('href', '/admin/inbox?type=INQUIRY_UNANSWERED')
    // Track 102 FE-64: 칸마다 "무엇을 센 건지 · 어디서 처리하는지" 한 줄. 근사 집계 3칸은 건수 차이를 알린다.
    await expect(page.getByTestId('dashboard-pending-hint')).toHaveCount(10)
    await expect(page.getByTestId('dashboard-pending-longShipping')).toContainText('발송 후 3일 이상')
    await expect(page.getByTestId('dashboard-pending-deliveryReady')).toContainText('다를 수 있음')

    // 0건도 "N건"으로 표시(응답 필드 누락이면 '—')
    await expect(page.getByTestId('dashboard-pending-claimFollowup').getByTestId('dashboard-pending-count')).toHaveText(/^\d{1,3}(,\d{3})*건$/)

    // 차트 2: apexcharts SVG가 카드 안에 그려진다(데이터 0이어도 축은 렌더)
    await expect(page.getByTestId('dashboard-chart-monthly').locator('svg.apexcharts-svg')).toBeVisible()
    await expect(page.getByTestId('dashboard-chart-daily').locator('svg.apexcharts-svg')).toBeVisible()

    // 리스트 4 + 전체 보기 링크
    for (const id of ['dashboard-recent-orders', 'dashboard-recent-claims', 'dashboard-top-sellers', 'dashboard-top-products']) {
      await expect(page.getByTestId(id)).toBeVisible()
      await expect(page.getByTestId(`${id}-all`)).toHaveAttribute('href', /^\/admin\//)
      // 행 또는 빈 상태 중 하나
      const rows = await page.getByTestId(`${id}-row`).count()
      if (rows === 0) await expect(page.getByTestId(`${id}-empty`)).toContainText('데이터 없음')
    }

    // FE-101: 클레임 처리 대기 타일 → 인박스 유형 필터로 이동(마지막에 이동·이전 단언과 무간섭)
    await page.getByTestId('dashboard-pending-claimFollowup').click()
    // 인박스는 PC 폭에서 첫 항목을 자동 선택해 URL에 &selected=…가 붙을 수 있어 type만 본다(실 BE 데모 데이터 유무와 무관하게 안정)
    await page.waitForURL((url) => url.pathname === '/admin/inbox' && url.searchParams.get('type') === 'CLAIM_FOLLOWUP')
    await expect(page.getByTestId('inbox-type-chip-CLAIM_FOLLOWUP')).toHaveClass(/v-chip--variant-flat/)
  })
})
