import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { mockSellerMe, pickOption } from './helpers/seller-mock'

/**
 * 셀러 상품 목록 화면(Track 90-C-3·90-C-1 API) E2E. 로그인은 loginAs(SELLER·BE 로그인 API·데모 경유 없음), 상품 목록·카테고리는 page.route mock.
 * /seller/**는 CSR 전용(ssr:false)이라 page.goto로도 mock을 거친다(기존 셀러 spec 동형). ① 목록 로드(칩·가격·등록일·수정 비활성) → 상태 필터(URL·API) →
 * 카테고리 필터 → 페이지 이동(page=1·API page) → 초기화 ② 빈 상태(필터 없음/있음 문구 분기) → 사이드바 활성
 * ③ 판매중지·재판매 셀프 전환(Track 96-5·D-206): 행 메뉴 → 확인 다이얼로그 → POST sale-status(mock 상태 갱신) → 재조회 반영 → 관리자 중지 행은 재판매 비활성.
 */
const PRODUCTS = Array.from({ length: 21 }, (_, index) => ({
  productPublicId: `prd_E2E${String(index + 1).padStart(23, '0')}`,
  name: index === 0 ? 'E2E 반찬통' : `E2E 상품 ${index + 1}`,
  categoryId: index % 2 === 0 ? 11 : 12,
  categoryName: index % 2 === 0 ? '주방' : '생활',
  status: index === 0 ? 'SALE' : index === 1 ? 'PENDING' : 'STOPPED',
  // D-206: STOPPED 행은 주체를 가진다 — index 2는 셀러 중지(재판매 가능), 나머지는 관리자 중지(재판매 비활성).
  saleStopSource: index === 0 || index === 1 ? undefined : index === 2 ? 'SELLER' : 'ADMIN',
  basePrice: 32000 + index * 1000,
  thumbnailUrl: index === 0 ? '/api/v1/files/products/e2e/thumb.jpg' : undefined,
  variantCount: index === 0 ? 3 : 1,
  createdAt: '2026-09-17T17:29:23+09:00',
  updatedAt: '2026-09-17T17:29:23+09:00',
}))

const CATEGORIES = [
  { categoryId: 11, displayName: '주방', sortOrder: 0 },
  { categoryId: 12, displayName: '생활', sortOrder: 1 },
]

interface Captured { listQueries: URLSearchParams[]; saleStatusBodies: { productPublicId: string; status: string }[] }

async function mockSellerProducts(page: Page, items = PRODUCTS): Promise<Captured> {
  const captured: Captured = { listQueries: [], saleStatusBodies: [] }
  await mockSellerMe(page)
  // 판매 상태 전환(96-5): body.status로 mock 항목을 갱신해 재조회 결과에 반영한다(데모 BE 데이터 무접촉).
  await page.route((url) => /\/api\/v1\/seller\/products\/prd_[^/]+\/sale-status$/.test(url.pathname), (route) => {
    const productPublicId = new URL(route.request().url()).pathname.split('/').at(-2) ?? ''
    const body = route.request().postDataJSON() as { status: string }
    captured.saleStatusBodies.push({ productPublicId, status: body.status })
    const target = items.find((item) => item.productPublicId === productPublicId)
    if (target) {
      target.status = body.status
      target.saleStopSource = body.status === 'STOPPED' ? 'SELLER' : undefined
    }
    return route.fulfill({ json: { ...target, soldoutManual: false, images: [], optionGroups: [], variants: [] } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/categories'), (route) => route.fulfill({ json: CATEGORIES }))
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/products'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const status = query.get('status')
    const categoryId = query.get('categoryId')
    const page0 = Number(query.get('page') ?? '0')
    const size = Number(query.get('size') ?? '20')
    const filtered = items
      .filter((item) => !status || item.status === status)
      .filter((item) => !categoryId || String(item.categoryId) === categoryId)
    const slice = filtered.slice(page0 * size, page0 * size + size)
    return route.fulfill({ json: { items: slice, page: page0, size, totalCount: filtered.length, hasNext: page0 * size + size < filtered.length } })
  })
  return captured
}

test.describe('셀러 상품 목록 화면(90-C-3)', () => {
  test('① 목록 로드(20행·칩·가격·등록일·수정 버튼) → 상태 필터 SALE(URL·API) → 카테고리 필터 → 2페이지 이동 → 초기화', async ({ page }) => {
    const captured = await mockSellerProducts(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products')

    await expect(page.getByTestId('row-product-name')).toHaveCount(20)
    await expect(page.getByTestId('row-product-name').first()).toHaveText('E2E 반찬통')
    await expect(page.getByTestId('status-chip').first()).toHaveText('판매중')
    await expect(page.getByTestId('status-chip').first()).toHaveClass(/slr-chip--success/)
    await expect(page.getByTestId('status-chip').nth(1)).toHaveText('승인대기')
    await expect(page.getByTestId('row-base-price').first()).toHaveText('32,000원')
    await expect(page.getByTestId('row-created-at').first()).toHaveText('2026.09.17 17:29') // KST 오프셋 ISO → formatDateTime
    await expect(page.getByTestId('row-edit').first()).toBeEnabled() // 수정 화면(90-C-4)으로 이동
    expect(captured.listQueries[0]?.get('page')).toBe('0')
    expect(captured.listQueries[0]?.get('sort')).toBe('LATEST')
    expect(captured.listQueries[0]?.has('status')).toBe(false)

    // 상태 필터 → URL·API status=SALE·1행
    await pickOption(page, 'filter-status', '판매중')
    await expect(page).toHaveURL(/status=SALE/)
    await expect(page.getByTestId('row-product-name')).toHaveCount(1)
    await expect.poll(() => captured.listQueries.at(-1)?.get('status')).toBe('SALE')

    // 카테고리 필터(공개 카테고리 API 옵션) → URL·API categoryId=12·SALE 상품은 주방(11)이라 빈 결과(필터 있음 문구)
    await pickOption(page, 'filter-category', '생활')
    await expect(page).toHaveURL(/categoryId=12/)
    await expect(page.getByTestId('seller-product-empty')).toContainText('조건에 맞는 상품이 없습니다')
    await expect.poll(() => captured.listQueries.at(-1)?.get('categoryId')).toBe('12')

    // 초기화 → 필터 제거·20행
    await page.getByTestId('filter-reset').click()
    await expect(page).toHaveURL(/\/seller\/products$/)
    await expect(page.getByTestId('row-product-name')).toHaveCount(20)

    // 2페이지 → URL page=1·API page=1·1행(21번째)
    await page.getByRole('button', { name: /next page/i }).click()
    await expect(page).toHaveURL(/page=1/)
    await expect(page.getByTestId('row-product-name')).toHaveCount(1)
    await expect(page.getByTestId('row-product-name').first()).toHaveText('E2E 상품 21')
    await expect.poll(() => captured.listQueries.at(-1)?.get('page')).toBe('1')
  })

  test('② 상품 0건 → 빈 상태(등록 안내) · 사이드바 상품 활성·재고 링크', async ({ page }) => {
    await mockSellerProducts(page, [])
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products')

    await expect(page.getByTestId('seller-product-empty')).toContainText('등록된 상품이 없습니다')
    const sidebar = page.getByTestId('seller-sidebar')
    await expect(sidebar.locator('.v-list-item--active')).toHaveCount(1)
    await expect(sidebar.locator('.v-list-item--active')).toContainText('상품')
    await expect(sidebar.locator('a[href="/seller/products/inventory"]')).toHaveCount(1)
    // 비활성 항목 0 — 90-B-3의 7개에서 상품·재고(90-C-3)·클레임(90-D-1)·비밀번호 변경(90-D-2)·통계 매출·주문클레임·상품(90-E-1~3) 7개 전부 활성화
    await expect(sidebar.locator('.v-list-item--disabled')).toHaveCount(0)
  })

  test('③ 판매중지·재판매(96-5): SALE 행 메뉴 → 판매중지 확인 → POST STOPPED → 칩·셀러 판매중지 라벨 → 재판매 → POST SALE · 관리자 중지 행은 재판매 비활성 · PENDING 행 메뉴 없음', async ({ page }) => {
    const items = PRODUCTS.map((item) => ({ ...item }))
    const captured = await mockSellerProducts(page, items)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products')
    await expect(page.getByTestId('row-product-name')).toHaveCount(20)
    // 행 메뉴: 1행 SALE·3행~ STOPPED(19행) = 19 → PENDING(2행)만 없음
    await expect(page.getByTestId('row-menu')).toHaveCount(19)
    await expect(page.getByTestId('stop-source').first()).toHaveText('셀러 판매중지')
    await expect(page.getByTestId('stop-source').nth(1)).toHaveText('관리자 판매중지')

    // 판매중지
    await page.getByTestId('row-menu').first().click()
    await page.getByTestId('row-sale-action').click()
    await expect(page.getByTestId('seller-sale-status-dialog')).toBeVisible()
    await expect(page.getByTestId('sale-status-message')).toContainText('"E2E 반찬통"을(를) 판매중지')
    await page.getByTestId('sale-status-ok').click()
    await expect(page.getByTestId('seller-sale-status-dialog')).toBeHidden()
    expect(captured.saleStatusBodies).toEqual([{ productPublicId: PRODUCTS[0]!.productPublicId, status: 'STOPPED' }])
    await expect(page.getByTestId('status-chip').first()).toHaveText('판매중지')
    await expect(page.getByTestId('stop-source').first()).toHaveText('셀러 판매중지')

    // 재판매(셀러 중지 → 활성)
    await page.getByTestId('row-menu').first().click()
    const resumeItem = page.getByTestId('row-sale-action')
    await expect(resumeItem).toContainText('재판매')
    await expect(resumeItem).not.toHaveClass(/v-list-item--disabled/)
    await resumeItem.click()
    await page.getByTestId('sale-status-ok').click()
    await expect(page.getByTestId('seller-sale-status-dialog')).toBeHidden()
    expect(captured.saleStatusBodies.at(-1)).toEqual({ productPublicId: PRODUCTS[0]!.productPublicId, status: 'SALE' })
    await expect(page.getByTestId('status-chip').first()).toHaveText('판매중')

    // 관리자 중지 행(4행·index 3): 재판매 비활성 + 문의 안내
    await page.getByTestId('row-menu').nth(2).click()
    const blocked = page.getByTestId('row-sale-action')
    await expect(blocked).toHaveClass(/v-list-item--disabled/)
    await expect(blocked).toContainText('운영자에게 문의')
    await page.keyboard.press('Escape')
    expect(captured.saleStatusBodies).toHaveLength(2)
  })
})
