import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 상품 목록(FE-25) E2E. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 상품 API는 page.route로 mock해
 * 로컬 DB를 바꾸지 않고 결정적으로 검증한다(목록 렌더·필터→URL→새로고침 유지·품절 토글·일괄 결과·삭제 409 안내).
 */
const ITEMS = [
  {
    productPublicId: 'prd_E2E0000000000000000000001', name: 'E2E 판매중 상품', thumbnailUrl: 'https://img.invalid/none.png',
    sellerPublicId: 'slr_E2E1', sellerName: 'E2E셀러', categoryId: 1, categoryName: '데모', stockTotal: 12,
    status: 'SALE', soldOut: false, soldOutManual: false, basePrice: 19900, supplyPrice: 12000, createdAt: '2026-09-16T10:00:00+09:00',
  },
  {
    productPublicId: 'prd_E2E0000000000000000000002', name: 'E2E 판매대기 상품',
    sellerPublicId: 'slr_E2E1', sellerName: 'E2E셀러', categoryId: 1, categoryName: '데모', stockTotal: 0,
    status: 'PENDING', soldOut: true, soldOutManual: false, basePrice: 5000, saleEndAt: '2026-12-31T00:00:00+09:00', createdAt: '2026-09-15T10:00:00+09:00',
  },
]

interface Captured { listQueries: URLSearchParams[]; patches: { url: string; body: string }[]; bulkBodies: string[] }

async function mockAdminApi(page: Page, options: { deleteStatus?: number } = {}): Promise<Captured> {
  const captured: Captured = { listQueries: [], patches: [], bulkBodies: [] }
  await page.route('**/api/v1/admin/sellers', (route) =>
    route.fulfill({ json: [{ sellerPublicId: 'slr_E2E1', companyName: 'E2E셀러', status: 'ACTIVE' }] }))
  await page.route('**/api/v1/categories', (route) => route.fulfill({ json: [{ categoryId: 1, displayName: '데모', sortOrder: 0 }] }))
  await page.route('**/api/v1/admin/products/bulk/**', (route) => {
    captured.bulkBodies.push(route.request().postData() ?? '')
    route.fulfill({ json: {
      results: [
        { productPublicId: ITEMS[0].productPublicId, success: true },
        { productPublicId: ITEMS[1].productPublicId, success: false, code: 'PRODUCT_INVALID_STATE', message: 'x' },
      ],
      successCount: 1, failureCount: 1,
    } })
  })
  await page.route('**/api/v1/admin/products/*/soldout', (route) => {
    captured.patches.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    route.fulfill({ json: { productPublicId: ITEMS[0].productPublicId } })
  })
  await page.route('**/api/v1/admin/products/prd_*', (route) => {
    if (route.request().method() === 'DELETE') {
      const status = options.deleteStatus ?? 204
      if (status === 409) {
        return route.fulfill({ status, contentType: 'application/problem+json',
          json: { code: 'PRODUCT_HAS_ORDER_HISTORY', detail: '주문 이력이 있는 상품은 삭제할 수 없습니다.' } })
      }
      return route.fulfill({ status })
    }
    return route.continue()
  })
  await page.route('**/api/v1/admin/products?**', (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const filtered = query.get('status') ? ITEMS.filter((item) => item.status === query.get('status')) : ITEMS
    route.fulfill({ json: { items: filtered, page: 0, size: 20, totalCount: filtered.length, hasNext: false } })
  })
  return captured
}

test.describe('관리자 상품 목록(FE-25)', () => {
  test('① 목록 렌더(행 2·상태 chip·품절 표시·판매기간) → 상태 필터 적용 시 URL 반영·새로고침 후 필터 유지·API 파라미터 전달', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await expect(page.getByTestId('soldout-chip').nth(1)).toHaveText('품절(재고)')
    await expect(page.getByText('즉시 ~ 2026.12.31 00:00')).toBeVisible()
    await expect(page.getByTestId('admin-sidebar').getByText('상품 목록')).toBeVisible()

    await page.getByTestId('filter-status').click()
    await page.getByRole('option', { name: '판매중', exact: true }).click()
    await expect(page).toHaveURL(/status=SALE/)
    await expect(page.getByTestId('status-chip')).toHaveCount(1)

    await page.reload()
    await expect(page).toHaveURL(/status=SALE/)
    await expect(page.getByTestId('status-chip')).toHaveCount(1)
    expect(captured.listQueries.at(-1)?.get('status')).toBe('SALE')
    expect(captured.listQueries.at(-1)?.get('sort')).toBe('LATEST')
  })

  test('② 수동 품절 토글 ON → danger 토스트·chip danger / OFF → success 토스트·chip success (PATCH soldout 호출)', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    // 초기 chip 의미 색: 판매중=success·판매대기=warning / 재고 있음=success·품절(재고)=danger
    await expect(page.getByTestId('status-chip').first()).toHaveClass(/adm-chip--success/)
    await expect(page.getByTestId('status-chip').nth(1)).toHaveClass(/adm-chip--warning/)
    await expect(page.getByTestId('soldout-chip').first()).toHaveClass(/adm-chip--success/)
    await expect(page.getByTestId('soldout-chip').nth(1)).toHaveClass(/adm-chip--danger/)
    await page.waitForTimeout(500)
    await page.screenshot({ path: 'playwright-report/fe-25/list-chips-desktop.png' })

    await page.getByTestId('soldout-toggle').first().locator('input').click({ force: true })
    await expect(page.getByTestId('soldout-chip').first()).toHaveText('품절(수동)')
    await expect(page.getByTestId('soldout-chip').first()).toHaveClass(/adm-chip--danger/)
    const dangerToast = page.locator('[data-sonner-toast][data-type="error"]')
    await expect(dangerToast).toContainText('수동 품절을 켰습니다')
    // 우상단 배치: 토스터 컨테이너가 화면 오른쪽 상단에 있다
    const box = await page.locator('[data-sonner-toaster]').boundingBox()
    expect(box && box.y < 100 && box.x + box.width > 1000).toBe(true)
    await page.waitForTimeout(700) // 토스트 진입 애니메이션 완료 후 캡처
    await page.screenshot({ path: 'playwright-report/fe-25/toast-danger-desktop.png' })
    expect(captured.patches[0]?.url).toContain(`/api/v1/admin/products/${ITEMS[0].productPublicId}/soldout`)
    expect(JSON.parse(captured.patches[0]?.body ?? '{}')).toEqual({ soldOut: true })

    await page.getByTestId('soldout-toggle').first().locator('input').click({ force: true })
    await expect(page.getByTestId('soldout-chip').first()).toHaveClass(/adm-chip--success/)
    const successToast = page.locator('[data-sonner-toast][data-type="success"]')
    await expect(successToast).toContainText('수동 품절을 껐습니다')
    await page.waitForTimeout(700)
    await page.screenshot({ path: 'playwright-report/fe-25/toast-success-desktop.png' })
    expect(JSON.parse(captured.patches[1]?.body ?? '{}')).toEqual({ soldOut: false })
  })

  test('③ 전체 선택 → 일괄 상태 적용 → 확인 → 스낵바 성공 1/실패 1 + 결과 다이얼로그 실패 사유', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await page.getByTestId('admin-product-table').locator('thead input[type="checkbox"]').click({ force: true })
    await expect(page.getByTestId('bulk-count')).toHaveText('2개 선택')
    await page.getByTestId('bulk-status').click()
    await page.getByRole('option', { name: /판매중지로/ }).click()
    await page.getByTestId('bulk-status-apply').click()
    await expect(page.getByTestId('admin-bulk-confirm-dialog')).toContainText('2개 상품을 판매중지')
    await page.getByTestId('admin-bulk-confirm-dialog-ok').click()
    const warningToast = page.locator('[data-sonner-toast][data-type="warning"]')
    await expect(warningToast).toContainText('성공 1 / 실패 1')
    await page.waitForTimeout(700) // 토스트 진입·다이얼로그 퇴장 애니메이션 완료 후 캡처
    await page.screenshot({ path: 'playwright-report/fe-25/toast-warning-desktop.png' })
    await warningToast.getByRole('button', { name: '상세 보기' }).click()
    await expect(page.getByTestId('admin-bulk-result-dialog')).toContainText('허용되지 않는 전환')
    expect(JSON.parse(captured.bulkBodies[0] ?? '{}')).toEqual({ productPublicIds: ITEMS.map((item) => item.productPublicId), status: 'STOPPED' })
  })

  test('④ 삭제 → 409 PRODUCT_HAS_ORDER_HISTORY → 안내 다이얼로그 + "판매중지로 전환" 버튼 / 허용되지 않는 전이 메뉴 비활성 / 500 → error 토스트', async ({ page }) => {
    await mockAdminApi(page, { deleteStatus: 409 })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await page.getByTestId('row-menu').first().click()
    // SALE 상품: 판매중지만 허용·판매중/거부는 비활성
    await expect(page.getByTestId('row-status-STOPPED')).not.toHaveClass(/v-list-item--disabled/)
    await expect(page.getByTestId('row-status-SALE')).toHaveClass(/v-list-item--disabled/)
    await expect(page.getByTestId('row-status-REJECTED')).toHaveClass(/v-list-item--disabled/)
    await page.getByTestId('row-delete').click()
    await expect(page.getByTestId('admin-delete-dialog')).toContainText('상품 삭제')
    await page.getByTestId('admin-delete-dialog-ok').click()
    await expect(page.getByTestId('admin-order-history-dialog')).toBeVisible()
    await expect(page.getByTestId('admin-order-history-dialog')).toContainText('주문 이력이 있어 삭제할 수 없습니다')
    await expect(page.getByTestId('admin-order-history-dialog-ok')).toHaveText(/판매중지로 전환/)
    await page.getByTestId('admin-order-history-dialog-cancel').click()

    // 삭제 500 → error 토스트(우상단·빨강)
    await page.route('**/api/v1/admin/products/prd_*', (route) =>
      route.request().method() === 'DELETE'
        ? route.fulfill({ status: 500, contentType: 'application/problem+json', json: { code: 'INTERNAL_ERROR', detail: 'x' } })
        : route.continue())
    await page.getByTestId('row-menu').nth(1).click()
    await page.getByTestId('row-delete').click() // 메뉴 오버레이는 열린 것 1개만 DOM에 존재
    await page.getByTestId('admin-delete-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toContainText('서버 오류')
  })

  test('⑤ 모바일(390): 토스트가 상단 전폭으로 표시된다', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await page.getByTestId('soldout-toggle').first().locator('input').click({ force: true })
    const toast = page.locator('[data-sonner-toast][data-type="error"]') // 품절 ON = danger
    await expect(toast).toBeVisible()
    const box = await toast.boundingBox()
    expect(box && box.y < 100 && box.width > 300).toBe(true)
    await page.waitForTimeout(700) // 토스트 진입·다이얼로그 퇴장 애니메이션 완료 후 캡처
    await page.screenshot({ path: 'playwright-report/fe-25/toast-danger-mobile.png' })
  })

  test('⑥ 재고 필터(Track 89-A): select 적용 시 URL·API stockFilter 전달 → 대시보드 "재고 임박" 타일 클릭 시 stockFilter=LOW로 진입', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)

    await page.getByTestId('filter-stock').click()
    await page.getByRole('option', { name: '재고 0', exact: true }).click()
    await expect(page).toHaveURL(/stockFilter=OUT/)
    // toHaveURL은 내비게이션만 보장하고 API 도착은 보장하지 않는다 → 목록 호출이 기록될 때까지 poll
    await expect.poll(() => captured.listQueries.at(-1)?.get('stockFilter')).toBe('OUT')

    // 대시보드 타일(실 BE dashboard 응답)은 카운트와 무관하게 링크가 있어야 하고, 클릭하면 상품 목록 LOW 필터가 URL·select에 반영된다
    await page.goto('/admin')
    const tile = page.getByTestId('dashboard-pending-lowStock')
    await expect(tile).toHaveAttribute('href', '/admin/products?stockFilter=LOW')
    await tile.click()
    await expect(page).toHaveURL(/\/admin\/products\?stockFilter=LOW$/)
    await expect(page.getByTestId('filter-stock')).toContainText('재고 임박(1~5)')
    await expect.poll(() => captured.listQueries.at(-1)?.get('stockFilter')).toBe('LOW')
  })
})
