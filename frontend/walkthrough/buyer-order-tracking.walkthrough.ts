import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough } from './helpers/walkthrough'

/**
 * 구매자 시나리오 3(Track 99): 배송중 주문의 배송 정보를 확인한다.
 * 완료 조건 = 배송중 주문 상세 화면 도달. 택배사·송장번호가 실제로 보이는지는 assert하지 않고 관찰값(note)으로만 남긴다
 * (있는지/없는지 자체가 이번 라운드의 관찰 대상이므로 통과 조건으로 쓰지 않는다).
 */
test('구매자 · 배송중 주문 배송 정보 확인', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'buyer', 'order-tracking', '배송중 주문 배송 정보 확인')
  await loginAs(page, 'BUYER')

  await walkthrough.goto('/orders')
  await expect(page.getByRole('heading', { name: '주문 내역' })).toBeVisible()
  await walkthrough.shot('주문-목록')

  const shippingOrder = page.locator('a[href^="/orders/ord_"]').filter({ hasText: '배송중' }).first()
  await expect(shippingOrder).toBeVisible()
  await walkthrough.click(shippingOrder)
  await expect(page.getByText('총 결제금액')).toBeVisible()
  await walkthrough.shot('주문-상세-배송중')

  const deliveryBlock = page.getByTestId('item-delivery').first()
  const hasDeliveryBlock = await deliveryBlock.count() > 0
  walkthrough.note('배송 정보 블록', hasDeliveryBlock ? '노출' : '미노출')
  if (hasDeliveryBlock) {
    walkthrough.note('택배사', (await deliveryBlock.getByTestId('item-delivery-carrier').innerText()).trim())
    const trackingNo = deliveryBlock.getByTestId('item-delivery-tracking-no')
    walkthrough.note('송장번호', await trackingNo.count() > 0 ? (await trackingNo.innerText()).trim() : '미노출')
    const shippedAt = deliveryBlock.getByTestId('item-delivery-shipped-at')
    walkthrough.note('발송일', await shippedAt.count() > 0 ? (await shippedAt.innerText()).trim() : '미노출')
  }
  walkthrough.note('배송 조회 링크', await page.getByRole('link', { name: /배송.*조회|배송추적/ }).count() > 0 ? '노출' : '미노출')
  await walkthrough.shot('배송-정보-확인')

  walkthrough.finish()
})
