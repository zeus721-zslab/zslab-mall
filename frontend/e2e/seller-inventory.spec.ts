import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { SUSPENDED_PROBLEM, mockSellerMe, pagedResponse } from './helpers/seller-mock'

/**
 * 셀러 재고 화면(Track 90-C-3·90-C-1 목록 + D-112 입출고) E2E. 로그인은 loginAs(SELLER), 재고 목록·mark-inbound/outbound는 page.route mock.
 * ① 목록(가용 0 danger 칩·옵션 라벨) → 입고 다이얼로그 사유 미입력 검증(API 미호출) → 입고 POST 성공 → 토스트·재조회 → 출고 성공
 * ② 출고 400(BE VALIDATION_FAILED fieldErrors) → 필드 오류·다이얼로그 유지 ③ 입고 403 SELLER_SUSPENDED → danger 토스트 + 배너 + 세션 유지.
 */
const BLACK_ID = 'var_E2E0000000000000000000001'
const WHITE_ID = 'var_E2E0000000000000000000002'
const PRODUCT_ID = 'prd_E2E0000000000000000000001'

const ROWS = [
  { variantPublicId: BLACK_ID, productPublicId: PRODUCT_ID, productName: 'E2E 반찬통', optionLabel: '색상: 블랙', sellerSku: 'E2E-BLK', quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8, updatedAt: '2026-09-17T17:29:23+09:00' },
  { variantPublicId: WHITE_ID, productPublicId: PRODUCT_ID, productName: 'E2E 반찬통', optionLabel: '색상: 화이트', sellerSku: 'E2E-WHT', quantityOnHand: 0, quantityReserved: 0, quantityAvailable: 0, updatedAt: '2026-09-16T09:00:00+09:00' },
]

interface Captured { listQueries: URLSearchParams[]; adjusts: { url: string; body: unknown }[] }

async function mockSellerInventory(page: Page, options: { inboundStatus?: number; outboundStatus?: number } = {}): Promise<Captured> {
  const captured: Captured = { listQueries: [], adjusts: [] }
  await mockSellerMe(page)
  await page.route((url) => /\/api\/v1\/seller\/inventories\/var_[^/]+\/mark-(inbound|outbound)$/.test(url.pathname), (route) => {
    const url = route.request().url()
    const body = route.request().postDataJSON() as { quantity: number }
    captured.adjusts.push({ url, body })
    const inbound = url.endsWith('mark-inbound')
    const status = inbound ? options.inboundStatus : options.outboundStatus
    if (status === 403) return route.fulfill({ status: 403, json: SUSPENDED_PROBLEM })
    if (status === 400) {
      return route.fulfill({ status: 400, json: { type: 'about:blank', title: 'Bad Request', status: 400, code: 'VALIDATION_FAILED', detail: '입력값 오류', fieldErrors: [{ field: 'reason', message: '사유는 필수입니다.' }] } })
    }
    const onHand = inbound ? 10 + body.quantity : 10 - body.quantity
    return route.fulfill({ json: { variantPublicId: BLACK_ID, quantityOnHand: onHand, quantityReserved: 2, quantityAvailable: onHand - 2 } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/inventories'), (route) => {
    captured.listQueries.push(new URL(route.request().url()).searchParams)
    return route.fulfill({ json: pagedResponse(ROWS) })
  })
  return captured
}

test.describe('셀러 재고 화면(90-C-3)', () => {
  test('① 목록(2행·가용 0 danger 칩·옵션 라벨) → 입고: 사유 미입력 검증 → 입력 후 POST 성공 → 토스트·재조회 → 출고 POST 성공', async ({ page }) => {
    const captured = await mockSellerInventory(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products/inventory')

    await expect(page.getByTestId('row-product-name')).toHaveCount(2)
    await expect(page.getByTestId('row-option-label').first()).toHaveText('색상: 블랙')
    await expect(page.getByTestId('row-available').first()).toHaveText('8')
    await expect(page.getByTestId('row-available-chip')).toHaveCount(1) // 가용 0 행만
    await expect(page.getByTestId('row-available-chip')).toHaveClass(/slr-chip--danger/)
    expect(captured.listQueries[0]?.get('page')).toBe('0')

    // 입고 다이얼로그: 수량만 넣고 확인 → 사유 검증 → API 미호출
    await page.getByTestId('row-inbound').first().click()
    const dialog = page.getByTestId('seller-inventory-adjust-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByTestId('adjust-title')).toHaveText('입고 처리')
    await expect(dialog.getByTestId('adjust-item')).toContainText('E2E 반찬통 (색상: 블랙)')
    await dialog.getByTestId('adjust-quantity').locator('input').fill('5')
    await dialog.getByTestId('adjust-dialog-ok').click()
    await expect(dialog).toContainText('사유를 입력하세요.')
    expect(captured.adjusts).toHaveLength(0)

    // 사유 입력 → POST mark-inbound body → success 토스트 → 재조회
    await dialog.getByTestId('adjust-reason').locator('input').fill('추가 입고')
    const listCallsBefore = captured.listQueries.length
    await dialog.getByTestId('adjust-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('입고 5개 처리했습니다. 보유 15 · 가용 13')
    expect(captured.adjusts).toHaveLength(1)
    expect(captured.adjusts[0]?.url).toContain(`/api/v1/seller/inventories/${BLACK_ID}/mark-inbound`)
    expect(captured.adjusts[0]?.body).toEqual({ quantity: 5, reason: '추가 입고' })
    await expect(dialog).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listCallsBefore)

    // 출고 → POST mark-outbound
    await page.getByTestId('row-outbound').first().click()
    await expect(dialog.getByTestId('adjust-title')).toHaveText('출고 처리')
    await dialog.getByTestId('adjust-quantity').locator('input').fill('3')
    await dialog.getByTestId('adjust-reason').locator('input').fill('파손 폐기')
    await dialog.getByTestId('adjust-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('출고 3개 처리했습니다. 보유 7 · 가용 5')
    expect(captured.adjusts[1]?.url).toContain(`/api/v1/seller/inventories/${BLACK_ID}/mark-outbound`)
    expect(captured.adjusts[1]?.body).toEqual({ quantity: 3, reason: '파손 폐기' })
    await expect(dialog).toBeHidden()
  })

  test('② 출고 400 VALIDATION_FAILED(fieldErrors) → 사유 필드 오류·다이얼로그 유지 · ③ 입고 403 SELLER_SUSPENDED → danger 토스트 + 정지 배너 + 세션 유지', async ({ page }) => {
    const captured = await mockSellerInventory(page, { inboundStatus: 403, outboundStatus: 400 })
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products/inventory')
    await expect(page.getByTestId('seller-suspended-notice')).toHaveCount(0)

    // 클라이언트 검증은 통과시키고 BE 400 fieldErrors를 필드에 표시(다이얼로그 유지)
    await page.getByTestId('row-outbound').first().click()
    const dialog = page.getByTestId('seller-inventory-adjust-dialog')
    await dialog.getByTestId('adjust-quantity').locator('input').fill('1')
    await dialog.getByTestId('adjust-reason').locator('input').fill('x')
    await dialog.getByTestId('adjust-dialog-ok').click()
    await expect(dialog).toContainText('사유는 필수입니다.')
    await expect(dialog).toBeVisible()
    expect(captured.adjusts).toHaveLength(1)
    await dialog.getByTestId('adjust-dialog-close').click()
    await expect(dialog).toBeHidden()

    // 입고 403 → 호출부 danger 토스트(정지 문구) + 레이아웃 배너(배너에만 의존하지 않음) · 다이얼로그 닫힘 · 세션 유지
    await page.getByTestId('row-inbound').first().click()
    await dialog.getByTestId('adjust-quantity').locator('input').fill('2')
    await dialog.getByTestId('adjust-reason').locator('input').fill('정지 테스트')
    await dialog.getByTestId('adjust-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('정지 상태의 셀러는 변경 작업을 할 수 없습니다')
    await expect(page.getByTestId('seller-suspended-notice')).toBeVisible()
    await expect(dialog).toBeHidden()
    expect(captured.adjusts).toHaveLength(2)
    await expect(page).toHaveURL(/\/seller\/products\/inventory/)
  })
})
