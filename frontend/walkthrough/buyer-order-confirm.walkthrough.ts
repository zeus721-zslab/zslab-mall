import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 구매자 시나리오 1(Track 98): 배송완료 주문을 구매확정한다.
 * 완료 조건 = 품목 아래 '구매확정이 완료되었습니다.' 안내.
 */
test('구매자 · 배송완료 주문 구매확정', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'buyer', 'order-confirm', '배송완료 주문 구매확정')
  await loginAs(page, 'BUYER')

  await walkthrough.goto('/orders')
  await expect(page.getByRole('heading', { name: '주문 내역' })).toBeVisible()
  await walkthrough.shot('주문-목록')

  // 반품 신청 시나리오(첫 번째 배송완료 주문)와 겹치지 않도록 마지막 배송완료 주문을 쓴다.
  const deliveredOrder = page.getByTestId('order-card').filter({ hasText: '배송완료' }).last()
  await expect(deliveredOrder).toBeVisible()
  await walkthrough.click(deliveredOrder)
  await expect(page.getByTestId('item-confirm-purchase').first()).toBeVisible()
  await walkthrough.shot('주문-상세-배송완료')

  await walkthrough.click(page.getByTestId('item-confirm-purchase').first())
  await expect(page.getByTestId('item-confirm-panel')).toBeVisible()
  await walkthrough.shot('구매확정-확인-패널')

  await walkthrough.click(page.getByTestId('item-confirm-submit'))
  // 완료 조건: 인라인 성공 안내.
  await expect(page.getByTestId('item-confirm-notice')).toHaveText('구매확정이 완료되었습니다.')
  await walkthrough.shot('구매확정-완료')

  walkthrough.finish()
})
