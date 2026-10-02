import { expect, test, type Page } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture, seedRows } from './manual-capture'

/**
 * 셀러 매뉴얼 캡처 — 주문·배송 처리. 화면을 열고 다이얼로그를 띄우기만 하며 확인 버튼은 누르지 않는다(상태 전이 없음).
 * 영역 키는 layers/seller/app/lib/seller-manual/order-delivery.ts 콜아웃의 region과 1:1이다.
 *
 * 데이터 전제(scripts/demo-seed/seed.py orders 단계): 로그인 셀러에게 결제완료(PAID) 품목 1건 이상 · 배송중(SHIPPING) 원 발송 1건 이상.
 */
const SAMPLE_TRACKING_NO = '123456789012'

test.beforeEach(async ({ page }) => {
  await loginAs(page, 'SELLER')
})

function paidRow(page: Page) {
  return seedRows(page.getByTestId('seller-order-table').locator('tbody tr')).filter({ has: page.getByTestId('row-prepare-shipment') }).first()
}

function shippingRow(page: Page) {
  return seedRows(page.getByTestId('seller-delivery-table').locator('tbody tr')).filter({ has: page.getByTestId('row-menu') }).first()
}

test('결제완료 품목 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/orders?status=PAID')
  await waitScreen(page, 'seller-order-table')
  const row = paidRow(page)
  await expect(row).toBeVisible()
  await capture.shot('order-paid-list', {
    statusFilter: page.getByTestId('filter-status'),
    orderNo: row.getByTestId('row-order-no'),
    detail: row.getByTestId('row-open'),
    ship: row.getByTestId('row-prepare-shipment'),
  })
})

test('발송 처리 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/orders?status=PAID')
  await waitScreen(page, 'seller-order-table')
  await paidRow(page).getByTestId('row-prepare-shipment').click()
  await waitScreen(page, 'seller-shipment-dialog')
  await page.getByTestId('shipment-carrier').click()
  await page.getByRole('option', { name: 'CJ대한통운', exact: true }).first().click()
  await page.getByTestId('shipment-tracking-no').locator('input').first().fill(SAMPLE_TRACKING_NO)
  await capture.shot('order-ship-dialog', {
    item: page.getByTestId('shipment-item'),
    carrier: page.getByTestId('shipment-carrier'),
    trackingNo: page.getByTestId('shipment-tracking-no'),
    confirm: page.getByTestId('shipment-dialog-ok'),
  }, { focus: page.getByTestId('seller-shipment-dialog') })
  await page.getByTestId('shipment-dialog-close').click()
})

test('배송중 목록과 행 메뉴', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/deliveries?status=SHIPPING')
  await waitScreen(page, 'seller-delivery-table')
  const row = shippingRow(page)
  await expect(row).toBeVisible()
  await row.getByTestId('row-menu').click()
  await expect(page.getByTestId('row-correct-tracking')).toBeVisible()
  await capture.shot('delivery-menu', {
    scope: page.getByTestId('filter-scope'),
    copy: row.getByTestId('row-copy-tracking'),
    markDelivered: page.getByTestId('row-mark-delivered'),
    correct: page.getByTestId('row-correct-tracking'),
  })
})

test('송장 정정 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/deliveries?status=SHIPPING')
  await waitScreen(page, 'seller-delivery-table')
  await shippingRow(page).getByTestId('row-menu').click()
  await page.getByTestId('row-correct-tracking').click()
  await waitScreen(page, 'seller-tracking-dialog')
  await capture.shot('delivery-tracking-dialog', {
    carrier: page.getByTestId('tracking-carrier'),
    trackingNo: page.getByTestId('tracking-no'),
    reason: page.getByTestId('tracking-reason'),
    confirm: page.getByTestId('tracking-dialog-ok'),
  }, { focus: page.getByTestId('seller-tracking-dialog') })
  await page.getByTestId('tracking-dialog-cancel').click()
})

test('배송완료 처리 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/deliveries?status=SHIPPING')
  await waitScreen(page, 'seller-delivery-table')
  await shippingRow(page).getByTestId('row-menu').click()
  await page.getByTestId('row-mark-delivered').click()
  await waitScreen(page, 'seller-mark-delivered-dialog')
  await capture.shot('delivery-mark-delivered', {
    notice: page.getByTestId('delivered-notice'),
    target: page.getByTestId('delivered-target'),
    confirm: page.getByTestId('delivered-dialog-ok'),
  }, { focus: page.getByTestId('seller-mark-delivered-dialog') })
  await page.getByTestId('delivered-dialog-close').click()
})
