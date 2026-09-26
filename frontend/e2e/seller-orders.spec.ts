import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { SUSPENDED_PROBLEM, mockSellerMe, pagedResponse, pickOption } from './helpers/seller-mock'

/**
 * 셀러 주문(품목) 화면(Track 90-B-3·D-191) E2E. 로그인은 loginAs(SELLER), 품목 목록·상세·발송(POST prepare-shipment)은 page.route mock.
 * ① 목록 → 상태 필터(URL·API status) → 상세(배송지 전체) → 발송 성공 ② 발송 403 SELLER_SUSPENDED → 호출부 danger 토스트 + 레이아웃 배너(둘 다).
 */
const PAID_ID = 'oit_E2E0000000000000000000001'
const SHIPPING_ID = 'oit_E2E0000000000000000000002'

const ITEMS = [
  { orderItemId: PAID_ID, orderNo: '20260917-E2E1', orderedAt: '2026-09-17T17:29:23+09:00', paidAt: '2026-09-17T17:32:23+09:00', productName: 'E2E 반찬통', quantity: 1, unitPrice: 32000, totalPrice: 32000, itemStatus: 'PAID', recipientName: '한시우' },
  { orderItemId: SHIPPING_ID, orderNo: '20260916-E2E2', orderedAt: '2026-09-16T09:00:00+09:00', paidAt: '2026-09-16T09:05:00+09:00', productName: 'E2E 주전자', optionLabel: '블랙', quantity: 2, unitPrice: 45000, totalPrice: 90000, itemStatus: 'SHIPPING', recipientName: '박지훈',
    delivery: { deliveryId: 'dlv_E2E2', carrier: 'HANJIN', trackingNo: 'E2E-TRK-0002', status: 'SHIPPING', shippedAt: '2026-09-16T12:00:00+09:00' } },
]

const DETAIL = {
  ...ITEMS[0],
  recipientName: undefined,
  shippingAddress: { recipientName: '한시우', recipientPhone: '010-2000-0000', zonecode: '16489', addressRoad: '경기 수원시 영통구 광교로 145', addressDetail: '101호', deliveryMemo: '문 앞' },
}

interface Captured { listQueries: URLSearchParams[]; shipments: { url: string; body: unknown }[] }

async function mockSellerOrders(page: Page, shipmentStatus = 200): Promise<Captured> {
  const captured: Captured = { listQueries: [], shipments: [] }
  await mockSellerMe(page)
  await page.route((url) => /\/api\/v1\/order-items\/oit_[^/]+\/prepare-shipment$/.test(url.pathname), (route) => {
    captured.shipments.push({ url: route.request().url(), body: route.request().postDataJSON() })
    if (shipmentStatus === 403) return route.fulfill({ status: 403, json: SUSPENDED_PROBLEM })
    return route.fulfill({ json: { deliveryPublicId: 'dlv_E2E_NEW', status: 'SHIPPING', carrier: 'CJ', trackingNo: 'E2E-NEW-0001' } })
  })
  await page.route((url) => /\/api\/v1\/seller\/order-items\/oit_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: DETAIL }))
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/order-items'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const status = query.get('status')
    return route.fulfill({ json: pagedResponse(status ? ITEMS.filter((item) => item.itemStatus === status) : ITEMS) })
  })
  return captured
}

test.describe('셀러 주문 화면(90-B-3)', () => {
  test('① 진입(품목 2행·PAID만 발송 버튼) → 상태 필터 PAID(URL·API) → 상세(배송지 전체·배송 화면 안내) → 발송 다이얼로그 → POST 성공 → 토스트·재조회', async ({ page }) => {
    const captured = await mockSellerOrders(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/orders')

    await expect(page.getByTestId('row-order-no')).toHaveCount(2)
    await expect(page.getByTestId('status-chip').first()).toHaveText('결제완료')
    await expect(page.getByTestId('status-chip').first()).toHaveClass(/slr-chip--info/)
    await expect(page.getByTestId('delivery-status-chip')).toHaveCount(1) // SHIPPING 행만 배송 chip
    await expect(page.getByTestId('row-prepare-shipment')).toHaveCount(1) // PAID 행만 발송
    await expect(page.getByTestId('row-paid-at').first()).toHaveText('2026.09.17 17:32') // KST 오프셋 ISO → formatDateTime
    expect(captured.listQueries[0]?.get('page')).toBe('0')
    expect(captured.listQueries[0]?.has('status')).toBe(false)

    // 상태 필터 → URL·API status=PAID·1행
    await pickOption(page, 'filter-status', '결제완료')
    await expect(page).toHaveURL(/status=PAID/)
    await expect(page.getByTestId('row-order-no')).toHaveCount(1)
    await expect.poll(() => captured.listQueries.at(-1)?.get('status')).toBe('PAID')

    // 상세: 주문번호 클릭 → back=목록 URL · 배송지 전체 · 발송 전 안내
    await page.getByTestId('row-order-no').first().click()
    await page.waitForURL(new RegExp(`/seller/orders/${PAID_ID}\\?back=`))
    await expect(page.getByTestId('order-detail-status')).toHaveText('결제완료')
    await expect(page.getByTestId('order-detail-recipient')).toContainText('한시우')
    await expect(page.getByTestId('order-detail-recipient')).toContainText('010-2000-0000')
    await expect(page.getByTestId('order-detail-address')).toContainText('[16489] 경기 수원시 영통구 광교로 145 101호')
    await expect(page.getByTestId('order-detail-address')).toContainText('메모: 문 앞')
    await expect(page.getByTestId('order-detail-no-delivery')).toContainText('아직 발송 전')
    await expect(page.getByTestId('order-detail-prepare-shipment')).toBeVisible()
    await page.getByTestId('order-detail-back').click()
    await expect(page).toHaveURL(/\/seller\/orders\?status=PAID$/)

    // 발송: 행 버튼 → 다이얼로그 → 검증(택배사·송장) → 입력 → POST body → info 토스트 → 목록 재조회
    await page.getByTestId('row-prepare-shipment').click()
    const dialog = page.getByTestId('seller-shipment-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByTestId('shipment-item')).toContainText('E2E 반찬통 · 1개')
    await dialog.getByTestId('shipment-dialog-ok').click()
    await expect(dialog).toContainText('택배사를 선택하세요.')
    expect(captured.shipments).toHaveLength(0)
    await pickOption(page, 'shipment-carrier', 'CJ대한통운')
    await dialog.getByTestId('shipment-tracking-no').locator('input').fill('E2E-NEW-0001')
    const listCallsBefore = captured.listQueries.length
    await dialog.getByTestId('shipment-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('발송 처리했습니다: CJ대한통운 E2E-NEW-0001')
    expect(captured.shipments).toHaveLength(1)
    expect(captured.shipments[0]?.url).toContain(`/api/v1/order-items/${PAID_ID}/prepare-shipment`)
    expect(captured.shipments[0]?.body).toEqual({ carrier: 'CJ', trackingNo: 'E2E-NEW-0001' })
    await expect(dialog).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listCallsBefore)
  })

  test('② 발송 403 SELLER_SUSPENDED → 호출부 danger 토스트(정지 문구) + 레이아웃 정지 배너(배너에만 의존하지 않음)', async ({ page }) => {
    const captured = await mockSellerOrders(page, 403)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/orders')
    await expect(page.getByTestId('seller-suspended-notice')).toHaveCount(0)

    await page.getByTestId('row-prepare-shipment').click()
    await pickOption(page, 'shipment-carrier', '한진택배')
    await page.getByTestId('shipment-tracking-no').locator('input').fill('E2E-403-0001')
    await page.getByTestId('shipment-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('정지 상태의 셀러는 변경 작업을 할 수 없습니다')
    await expect(page.getByTestId('seller-suspended-notice')).toBeVisible()
    await expect(page.getByTestId('seller-shipment-dialog')).toBeHidden()
    expect(captured.shipments).toHaveLength(1)
    // 세션은 유지된다(로그인 화면으로 가지 않음)
    await expect(page).toHaveURL(/\/seller\/orders/)
  })
})
