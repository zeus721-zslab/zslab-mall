import { test, expect, type Page } from '@playwright/test'

/**
 * 관리자 카테고리 관리(FE-38·Track 89-C) E2E 스모크. 로그인은 데모 버튼(NUXT_ADMIN_DEMO_* 주입 환경·미주입 시 skip), 카테고리 API는 page.route로
 * mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(진입 → 율 표기·기본율 병기 → 순서 이동(PATCH /order 본문) → 수정 다이얼로그(율 변경 시 사유 필수·
 * 경고 3문장) → 삭제 비활성 툴팁·활성 행 확인 다이얼로그 노출까지. PUT·DELETE는 호출하지 않는다).
 */
const ITEMS = [
  { categoryId: 1, displayName: 'E2E 리빙', sortOrder: 0, productCount: 7, createdAt: '2026-09-18T11:45:09+09:00' },
  { categoryId: 2, displayName: 'E2E 의류', sortOrder: 1, commissionRate: 525, productCount: 5, createdAt: '2026-09-18T11:45:09+09:00' },
  { categoryId: 3, displayName: 'E2E 빈 카테고리', sortOrder: 2, productCount: 0, createdAt: '2026-09-18T11:45:09+09:00' },
]

interface Captured { reorders: string[]; mutations: string[] }

async function mockAdminApi(page: Page): Promise<Captured> {
  const captured: Captured = { reorders: [], mutations: [] }
  let order = ITEMS.map((item) => item.categoryId)
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/categories/order'), (route) => {
    const body = route.request().postDataJSON() as { categoryIds: number[] }
    captured.reorders.push(JSON.stringify(body.categoryIds))
    order = body.categoryIds
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => /\/api\/v1\/admin\/categories\/\d+$/.test(url.pathname), (route) => {
    captured.mutations.push(`${route.request().method()} ${route.request().url()}`)
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/categories'), (route) => {
    if (route.request().method() !== 'GET') {
      captured.mutations.push(`${route.request().method()} ${route.request().url()}`)
      return route.fulfill({ status: 201, json: { categoryId: 99, displayName: 'x', depth: 1, sortOrder: 3 } })
    }
    const items = order.map((id, index) => ({ ...ITEMS.find((item) => item.categoryId === id)!, sortOrder: index }))
    return route.fulfill({ json: { defaultCommissionRate: 1000, items } })
  })
  return captured
}

async function loginByDemo(page: Page): Promise<void> {
  await page.goto('/admin/login')
  await page.waitForLoadState('networkidle')
  const demoButton = page.getByTestId('admin-demo-login')
  test.skip((await demoButton.count()) === 0, 'NUXT_ADMIN_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
  await demoButton.click()
  await page.waitForURL(/\/admin$/)
}

test.describe('관리자 카테고리 관리(FE-38)', () => {
  test('① 진입(3행·율 표기·기본율 병기) → 아래로 이동(PATCH /order 전체 배열) → 수정 다이얼로그(율 변경 시 사유 필수·경고 3문장) → 삭제 비활성 툴팁·활성 행 확인 다이얼로그(PUT·DELETE 0)', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginByDemo(page)
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/products/categories')

    // 목록: 3행·순서 1~3·미설정은 기본율 병기·설정값은 %
    await expect(page.getByTestId('row-name')).toHaveCount(3)
    await expect(page.getByTestId('row-order').first()).toHaveText('1')
    await expect(page.getByTestId('row-commission-rate').first()).toHaveText('미설정 (기본율 10% 적용)')
    await expect(page.getByTestId('row-commission-rate').nth(1)).toHaveText('5.25%')
    await expect(page.getByTestId('row-product-count').first()).toHaveText('7')
    await expect(page.getByTestId('row-move-up').first()).toBeDisabled()
    await expect(page.getByTestId('row-move-down').last()).toBeDisabled()

    // 첫 행 아래로 → PATCH /order [2,1,3] → 재조회 후 순서 반영
    await page.getByTestId('row-move-down').first().click()
    await expect(page.getByTestId('row-name').first()).toHaveText('E2E 의류')
    expect(captured.reorders).toEqual(['[2,1,3]'])

    // 수정 다이얼로그(의류·5.25%): 이름·율 기본값·경고 3문장·율 변경 전엔 확인 활성 → 율 변경 시 사유 비면 비활성
    await page.getByTestId('row-edit').first().click()
    const dialog = page.getByTestId('admin-category-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByTestId('category-name').locator('input')).toHaveValue('E2E 의류')
    await expect(dialog.getByTestId('category-rate').locator('input')).toHaveValue('5.25')
    const warning = dialog.getByTestId('category-rate-warning')
    await expect(warning).toContainText('새로 생성되는 주문부터 적용')
    await expect(warning).toContainText('이미 생성된 주문·정산(재생성 포함)에는 영향이 없습니다')
    await expect(warning).toContainText('셀러 개별 수수료율이 설정된 셀러의 상품에는 적용되지 않습니다')
    await expect(dialog.getByTestId('category-dialog-ok')).toBeEnabled()
    await dialog.getByTestId('category-rate').locator('input').fill('7')
    await expect(dialog.getByTestId('category-dialog-ok')).toBeDisabled() // 율 변경·사유 비어 있음
    await expect(dialog.getByTestId('category-reason')).toContainText('변경 사유 (필수)')
    await dialog.getByTestId('category-dialog-cancel').click()
    await expect(dialog).toBeHidden()

    // 삭제: 상품 7건 행 비활성 + 툴팁 / 상품 0건 행 활성 → 확인 다이얼로그 노출까지
    const linkedRow = page.getByTestId('row-delete').nth(1) // 이동 후 2번째 = E2E 리빙(7건)
    await expect(linkedRow).toBeDisabled()
    await page.getByTestId('row-delete-wrapper').nth(1).hover()
    // 툴팁 내용은 활성화 시 지연 렌더라 행 인덱스와 어긋난다 → 문구로 찾는다.
    await expect(page.getByTestId('row-delete-blocked').filter({ hasText: '연결된 상품 7건' })).toBeVisible()
    await expect(page.getByTestId('row-delete').last()).toBeEnabled()
    await page.getByTestId('row-delete').last().click()
    const confirm = page.getByTestId('admin-category-delete-dialog')
    await expect(confirm).toBeVisible()
    await expect(confirm).toContainText('E2E 빈 카테고리')
    await confirm.getByTestId('admin-category-delete-dialog-cancel').click()
    await expect(confirm).toBeHidden()

    expect(captured.mutations).toHaveLength(0)
  })
})
