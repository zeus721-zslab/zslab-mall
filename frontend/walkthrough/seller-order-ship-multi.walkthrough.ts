import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 셀러 시나리오 3(Track 99·다건): 배송 대기 품목 3건을 연속으로 출고한다.
 * 완료 조건 = 3건 모두 결제완료(배송 대기) 목록에서 빠진다.
 *
 * 계측은 segment로 건별로 나눈다(1건째는 대시보드 → 목록 진입 비용을 포함하고 2·3건째는 목록에서 바로 반복하는 비용만 담는다).
 */
const SHIP_COUNT = 3

test('셀러 · 배송 대기 3건 연속 출고', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'seller', 'order-ship-multi', '배송 대기 3건 연속 출고')
  await loginAs(page, 'SELLER')

  walkthrough.segment('1건째')
  await walkthrough.goto('/seller')
  await waitScreen(page, 'seller-dashboard')
  await walkthrough.shot('대시보드-배송대기-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-deliveryReady'))
  await waitScreen(page, 'seller-order-table')
  await walkthrough.shot('주문-목록-결제완료')

  for (let index = 1; index <= SHIP_COUNT; index += 1) {
    if (index > 1) walkthrough.segment(index + '건째')
    const row = page.getByTestId('seller-order-table').locator('tbody tr')
      .filter({ has: page.getByTestId('row-prepare-shipment') }).first()
    await expect(row).toBeVisible()
    const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
    walkthrough.note(index + '건째 주문번호', orderNo)

    await walkthrough.click(row.getByTestId('row-prepare-shipment'))
    await expect(page.getByTestId('seller-shipment-dialog')).toBeVisible()
    await walkthrough.shot(index + '건째-출고-다이얼로그')

    // Track 99 FE-61: 직전 출고 택배사가 기본 선택돼 2건째부터는 택배사를 다시 고르지 않는다(1건째만 고른다).
    if (index === 1) {
      await walkthrough.select(page.getByTestId('shipment-carrier'), 'CJ대한통운')
    } else {
      await expect(page.getByTestId('shipment-carrier')).toContainText('CJ대한통운')
    }
    // 송장번호는 UNIQUE(DLV-1)라 실행 시각으로 만든다.
    await walkthrough.fill(page.getByTestId('shipment-tracking-no').locator('input').first(), 'WTM' + index + Date.now())
    await walkthrough.click(page.getByTestId('shipment-dialog-ok'))
    await expect(page.getByTestId('seller-shipment-dialog')).toBeHidden()
    // 완료 조건(건별): 출고한 품목은 결제완료 목록에서 빠진다.
    await expect(page.getByTestId('seller-order-table').locator('tbody tr').filter({ hasText: orderNo })).toHaveCount(0)
    await walkthrough.shot(index + '건째-출고-완료')
  }

  walkthrough.finish()
})
