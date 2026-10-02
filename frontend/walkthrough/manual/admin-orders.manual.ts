import { expect, test, type Page } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture, seedRows } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 주문·배송. 메뉴·다이얼로그는 열기만 하고 확인 버튼은 누르지 않는다.
 * 영역 키는 layers/admin/app/lib/admin-manual/order-delivery.ts와 1:1이다.
 * 데이터 전제(seed.py orders): 결제완료(PAID) 주문 1건 이상 · 배송중(SHIPPING) 원 발송 1건 이상.
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

function paidRow(page: Page) {
  return seedRows(page.getByTestId('admin-order-table').locator('tbody tr')).filter({ has: page.getByTestId('row-menu') }).first()
}

async function openPaidOrder(page: Page): Promise<void> {
  await page.goto('/admin/orders?status=PAID')
  await waitScreen(page, 'admin-order-table')
  await paidRow(page).getByTestId('row-open').click()
  await waitScreen(page, 'order-summary')
}

function shippingRow(page: Page) {
  return seedRows(page.getByTestId('admin-delivery-table').locator('tbody tr')).first()
}

test('주문 목록과 행 메뉴', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/orders?status=PAID')
  await waitScreen(page, 'admin-order-table')
  const row = paidRow(page)
  await expect(row).toBeVisible()
  await row.getByTestId('row-menu').click()
  await expect(page.getByTestId('row-prepare-shipment')).toBeVisible()
  await capture.shot('order-list', {
    filters: page.getByTestId('admin-order-filters'),
    status: page.getByTestId('filter-status'),
    detail: row.getByTestId('row-open'),
    ship: page.getByTestId('row-prepare-shipment'),
  })
})

test('주문 상세', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await openPaidOrder(page)
  await capture.shot('order-detail', {
    cancel: page.getByTestId('open-cancel'),
    ship: page.getByTestId('open-shipment'),
    items: page.getByTestId('order-items'),
    back: page.getByTestId('back-to-list'),
  })
})

test('주문 취소 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await openPaidOrder(page)
  await page.getByTestId('open-cancel').click()
  const dialog = page.getByTestId('admin-order-cancel-dialog')
  await expect(dialog).toBeVisible()
  await capture.shot('order-cancel', {
    items: dialog.getByTestId('cancel-items'),
    reason: dialog.getByTestId('cancel-reason'),
    detail: dialog.getByTestId('cancel-reason-detail'),
    confirm: dialog.getByTestId('cancel-dialog-ok'),
  }, { focus: dialog })
  await dialog.getByTestId('cancel-dialog-close').click()
})

test('배송 관리 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/orders/deliveries?status=SHIPPING')
  await waitScreen(page, 'admin-delivery-table')
  const row = shippingRow(page)
  await expect(row).toBeVisible()
  await capture.shot('delivery-list', {
    scope: page.getByTestId('filter-scope'),
    status: page.getByTestId('filter-status'),
    trackingNo: row.getByTestId('row-tracking-no'),
    copy: row.getByTestId('row-copy-tracking'),
  })
})

test('송장 정정 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/orders/deliveries?status=SHIPPING')
  await waitScreen(page, 'admin-delivery-table')
  // 행 첫 칸의 주문번호는 링크라 상품 칸을 눌러 행 클릭(상세 다이얼로그)으로 연다.
  await shippingRow(page).locator('td').nth(1).click()
  await waitScreen(page, 'admin-delivery-detail-dialog')
  await page.getByTestId('delivery-correct-tracking').click()
  const dialog = page.getByTestId('admin-delivery-tracking-dialog')
  await expect(dialog).toBeVisible()
  await capture.shot('delivery-tracking', {
    carrier: dialog.getByTestId('tracking-carrier'),
    trackingNo: dialog.getByTestId('tracking-no'),
    reason: dialog.getByTestId('tracking-reason'),
    confirm: dialog.getByTestId('tracking-dialog-ok'),
  }, { focus: dialog })
  await dialog.getByTestId('tracking-dialog-cancel').click()
})
