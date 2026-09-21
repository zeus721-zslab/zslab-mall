import { test, expect } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 대시보드(FE-33) 스모크. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 대시보드 API는 실 BE(D-180)를 호출해
 * 요약 카드 6·처리 대기 7·차트 2(apexcharts SVG)·리스트 4가 렌더되는지 확인한다. 데이터 유무와 무관하게 성립하는 단언만 둔다.
 * 클레임 처리 대기 타일(Track 96-4)은 클릭 → 클레임 목록 ?action=FOLLOWUP 복원·필터 select 표시까지 확인한다.
 */
test.describe('관리자 대시보드 (FE-33)', () => {
  test('① 로그인 → /admin: 요약 카드 6·증감 배지·처리 대기 7·차트 2·리스트 4 렌더', async ({ page }) => {
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

    // 처리 대기 7칸: 링크 7(정산·클레임·배송·재고 임박=상품 목록 stockFilter=LOW·Track 89-A / 상품·셀러 승인 대기=status=PENDING·Track 96-2 / 클레임 처리 대기=action=FOLLOWUP·Track 96-4)
    await expect(page.getByTestId('dashboard-pending-count')).toHaveCount(7)
    await expect(page.getByTestId('dashboard-pending-settlementPending')).toHaveAttribute('href', '/admin/settlements?status=PENDING')
    await expect(page.getByTestId('dashboard-pending-claimRequested')).toHaveAttribute('href', '/admin/orders/claims?status=REQUESTED')
    await expect(page.getByTestId('dashboard-pending-deliveryReady')).toHaveAttribute('href', '/admin/orders?status=PAID')
    await expect(page.getByTestId('dashboard-pending-lowStock')).toHaveAttribute('href', '/admin/products?stockFilter=LOW')
    await expect(page.getByTestId('dashboard-pending-productPending')).toHaveAttribute('href', '/admin/products?status=PENDING')
    await expect(page.getByTestId('dashboard-pending-sellerPending')).toHaveAttribute('href', '/admin/members/sellers?status=PENDING')
    await expect(page.getByTestId('dashboard-pending-claimFollowup')).toHaveAttribute('href', '/admin/orders/claims?action=FOLLOWUP')
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

    // Track 96-4 FE-56: 클레임 처리 대기 타일 → 클레임 목록 action=FOLLOWUP 복원 → 필터 select "후속 처리 전체" 표시(마지막에 이동·이전 단언과 무간섭)
    await page.getByTestId('dashboard-pending-claimFollowup').click()
    await page.waitForURL(/\/admin\/orders\/claims\?action=FOLLOWUP$/)
    await expect(page.getByTestId('filter-action')).toContainText('후속 처리 전체')
  })
})
