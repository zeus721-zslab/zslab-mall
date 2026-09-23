import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 셀러 시나리오 1(Track 98): 대시보드 배송 대기에서 신규 주문 품목 1건을 발송 처리(송장 등록)한다.
 * 완료 조건 = 해당 행의 품목 상태 칩이 '배송중'.
 */
test('셀러 · 신규 주문 확인 → 발송 처리(송장 등록)', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'seller', 'order-ship', '신규 주문 → 발송 처리(송장 등록)')
  await loginAs(page, 'SELLER')

  await walkthrough.goto('/seller')
  await waitScreen(page, 'seller-dashboard')
  await walkthrough.shot('대시보드-배송대기-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-deliveryReady'))
  await waitScreen(page, 'seller-order-table')
  await walkthrough.shot('주문-목록-결제완료')

  const row = page.getByTestId('seller-order-table').locator('tbody tr')
    .filter({ has: page.getByTestId('row-prepare-shipment') }).first()
  await expect(row).toBeVisible()
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  await walkthrough.click(row.getByTestId('row-prepare-shipment'))
  await expect(page.getByTestId('seller-shipment-dialog')).toBeVisible()
  await walkthrough.shot('발송-다이얼로그')

  await walkthrough.select(page.getByTestId('shipment-carrier'), 'CJ대한통운')
  // 송장번호는 UNIQUE(DLV-1)라 실행 시각으로 만든다.
  await walkthrough.fill(page.getByTestId('shipment-tracking-no').locator('input').first(), 'WT' + Date.now())
  await walkthrough.shot('발송-정보-입력')

  await walkthrough.click(page.getByTestId('shipment-dialog-ok'))
  await expect(page.getByTestId('seller-shipment-dialog')).toBeHidden()
  // 완료 조건: 발송한 품목은 결제완료(배송 대기) 목록에서 빠진다.
  await expect(page.getByTestId('seller-order-table').locator('tbody tr').filter({ hasText: orderNo })).toHaveCount(0)
  await walkthrough.shot('발송-완료')

  walkthrough.finish()
})
