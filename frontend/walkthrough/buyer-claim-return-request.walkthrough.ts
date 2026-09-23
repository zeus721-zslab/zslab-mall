import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 구매자 시나리오 2(Track 98): 배송완료 품목에 반품을 신청한다.
 * 완료 조건 = '클레임이 접수되었습니다.' 화면.
 */
test('구매자 · 배송완료 품목 반품 신청', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'buyer', 'claim-return-request', '주문 조회 → 반품 신청')
  await loginAs(page, 'BUYER')

  await walkthrough.goto('/orders')
  await expect(page.getByRole('heading', { name: '주문 내역' })).toBeVisible()
  await walkthrough.shot('주문-목록')

  // 구매확정 시나리오와 겹치지 않도록 배송완료 주문 중 첫 번째를 쓴다(구매확정은 마지막 주문).
  const deliveredOrder = page.getByTestId('order-card').filter({ hasText: '배송완료' }).first()
  await expect(deliveredOrder).toBeVisible()
  await walkthrough.click(deliveredOrder)
  const returnButton = page.getByRole('button', { name: '반품 요청' }).first()
  await expect(returnButton).toBeVisible()
  await walkthrough.shot('주문-상세-반품가능')

  await walkthrough.click(returnButton)
  await expect(page.getByRole('heading', { name: '반품 요청' })).toBeVisible()
  await walkthrough.shot('반품-신청-폼')

  await walkthrough.selectOption(page.locator('#reasonCode'), 'BUYER_CHANGED_MIND')
  await walkthrough.fill(page.locator('#reasonDetail'), '워크스루 반품 신청 사유')
  await walkthrough.shot('반품-사유-입력')

  await walkthrough.click(page.getByTestId('claim-submit'))
  // 완료 조건: 접수 완료 화면.
  await expect(page.getByText('클레임이 접수되었습니다.')).toBeVisible()
  await walkthrough.shot('반품-접수-완료')

  walkthrough.finish()
})
