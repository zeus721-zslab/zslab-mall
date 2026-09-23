import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { mockSellerMe, pagedResponse } from './helpers/seller-mock'

/**
 * 셀러 정산 화면(Track 90-B-3·Track 85 BE·읽기 전용) E2E. 로그인은 loginAs(SELLER), /seller/me(확정 대기 건수)·정산 목록·상세·품목은 page.route mock.
 * ① 목록(확정 대기 안내·행) → 상세(금액·계좌 스냅샷) → 품목 탭 전환(URL tab·API type) → 목록 복귀 ② 확정 대기 정산 id 직접 진입 → 404 안내.
 */
const CONFIRMED = { id: 13, periodStart: '2026-07-01T00:00:00+09:00', periodEnd: '2026-07-31T23:59:59.999999+09:00', grossAmount: 352600, feeAmount: 35260, refundAmount: 0, carryoverAmount: 0, netAmount: 317340, status: 'CONFIRMED', scheduledPayDate: '2026-08-20' }
const PAID = { id: 10, periodStart: '2026-06-01T00:00:00+09:00', periodEnd: '2026-06-30T23:59:59.999999+09:00', grossAmount: 371700, feeAmount: 37170, refundAmount: 12000, carryoverAmount: 0, netAmount: 322530, status: 'PAID', scheduledPayDate: '2026-07-20', paidAt: '2026-07-20T10:00:00+09:00' }
const DETAIL = { ...CONFIRMED, saleItemCount: 2, refundItemCount: 1, carryoverItemCount: 0, bankAccount: { id: 1, bankCode: '004', accountHolder: '데모 셀러', accountNumberSuffix: '1234', snapshot: false } }
const SALE_ITEMS = [
  { id: 101, itemType: 'SALE', orderItemId: 1, orderPublicId: 'ord_E2E1', productName: 'E2E 냄비 세트', quantity: 1, amount: 89000, commissionRate: 1000, feeAmount: 8900, occurredAt: '2026-07-10T10:00:00+09:00' },
  { id: 102, itemType: 'SALE', orderItemId: 2, orderPublicId: 'ord_E2E2', productName: 'E2E 반찬통', optionLabel: '6종', quantity: 2, amount: 64000, commissionRate: 1000, feeAmount: 6400, occurredAt: '2026-07-12T10:00:00+09:00' },
]
const REFUND_ITEMS = [
  { id: 201, itemType: 'REFUND', orderItemId: 1, refundId: 9, orderPublicId: 'ord_E2E1', productName: 'E2E 냄비 세트', quantity: 1, amount: 89000, commissionRate: 1000, feeAmount: 0, occurredAt: '2026-07-15T10:00:00+09:00' },
]

interface Captured { itemQueries: URLSearchParams[] }

async function mockSellerSettlements(page: Page): Promise<Captured> {
  const captured: Captured = { itemQueries: [] }
  await mockSellerMe(page, { pendingSettlementCount: 1 })
  await page.route((url) => /\/api\/v1\/seller\/settlements\/\d+\/items$/.test(url.pathname), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.itemQueries.push(query)
    return route.fulfill({ json: pagedResponse(query.get('type') === 'REFUND' ? REFUND_ITEMS : SALE_ITEMS) })
  })
  await page.route((url) => /\/api\/v1\/seller\/settlements\/\d+$/.test(url.pathname), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    if (id === 13) return route.fulfill({ json: DETAIL })
    return route.fulfill({ status: 404, json: { type: 'about:blank', title: 'Not Found', status: 404, code: 'SETTLEMENT_NOT_FOUND', detail: '없음' } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/settlements'), (route) => route.fulfill({ json: pagedResponse([CONFIRMED, PAID]) }))
  return captured
}

test.describe('셀러 정산 화면(90-B-3)', () => {
  test('① 목록(확정 대기 1건 안내·2행·상태 chip) → 상세(금액·계좌 현재 주계좌·확정 안내) → 환불 탭(URL tab·API type=REFUND) → 목록 복귀', async ({ page }) => {
    const captured = await mockSellerSettlements(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/settlements')

    await expect(page.getByTestId('seller-settlement-pending-notice')).toContainText('확정 대기 정산 1건')
    await expect(page.getByTestId('seller-settlement-pending-notice')).toContainText('대시보드 "정산 예정"과 같은 건수')
    await expect(page.getByTestId('row-period')).toHaveText(['2026년 7월', '2026년 6월'])
    await expect(page.getByTestId('row-status')).toHaveText(['확정', '지급완료'])
    await expect(page.getByTestId('row-net').first()).toHaveText('317,340원')
    await expect(page.getByTestId('row-paid-at').nth(1)).toHaveText('2026.07.20 10:00')

    await page.getByTestId('row-open').first().click()
    await page.waitForURL(/\/seller\/settlements\/13\?back=/)
    await expect(page.getByTestId('settlement-status')).toHaveText('확정')
    await expect(page.getByTestId('settlement-confirmed-notice')).toBeVisible()
    await expect(page.getByTestId('settlement-net')).toHaveText('317,340원')
    await expect(page.getByTestId('settlement-scheduled')).toHaveText('2026.08.20')
    await expect(page.getByTestId('settlement-bank-account')).toHaveText('004 ···1234 (데모 셀러)')
    await expect(page.getByTestId('settlement-bank-source')).toHaveText('현재 주 정산계좌')
    await expect(page.getByTestId('item-product')).toHaveCount(2)
    expect(captured.itemQueries[0]?.get('type')).toBe('SALE')

    await page.getByTestId('settlement-tab-REFUND').click()
    await expect(page).toHaveURL(/tab=REFUND/)
    await expect(page.getByTestId('item-product')).toHaveCount(1)
    await expect(page.getByTestId('item-fee').first()).toHaveText('0원')
    await expect.poll(() => captured.itemQueries.at(-1)?.get('type')).toBe('REFUND')

    await page.getByTestId('settlement-back').click()
    await expect(page).toHaveURL(/\/seller\/settlements$/)
  })

  test('② 확정 대기(404) 정산 id 직접 진입 → 안내 카드·목록 이동 버튼', async ({ page }) => {
    await mockSellerSettlements(page)
    await loginAs(page, 'SELLER')
    await page.goto('/seller/settlements/99')
    await expect(page.getByTestId('settlement-not-found')).toContainText('아직 확정되지 않은 정산')
    await page.getByTestId('settlement-not-found').getByRole('link', { name: '목록으로' }).click()
    await expect(page).toHaveURL(/\/seller\/settlements$/)
  })
})
