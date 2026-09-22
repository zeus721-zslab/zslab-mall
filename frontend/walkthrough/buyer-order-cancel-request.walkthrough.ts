import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough } from './helpers/walkthrough'

/**
 * 구매자 시나리오 4(Track 99): 결제완료 주문의 품목에 취소를 신청한다.
 * 완료 조건 = '클레임이 접수되었습니다.' 화면.
 */
test('구매자 · 결제완료 주문 취소 신청', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'buyer', 'order-cancel-request', '결제완료 주문 취소 신청')
  await loginAs(page, 'BUYER')

  await walkthrough.goto('/orders')
  await expect(page.getByRole('heading', { name: '주문 내역' })).toBeVisible()
  await walkthrough.shot('주문-목록')

  // 결제완료 주문 중 가장 최근 건 = prepare.py가 마지막에 보장한 취소 가능(품목 PAID) 주문(목록은 주문일시 내림차순).
  const paidOrder = page.locator('a[href^="/orders/ord_"]').filter({ hasText: '결제완료' }).first()
  await expect(paidOrder).toBeVisible()
  await walkthrough.click(paidOrder)
  const cancelButton = page.getByRole('button', { name: '취소 요청' }).first()
  await expect(cancelButton).toBeVisible()
  await walkthrough.shot('주문-상세-취소가능')

  await walkthrough.click(cancelButton)
  await expect(page.getByRole('heading', { name: '취소 요청' })).toBeVisible()
  await walkthrough.shot('취소-신청-폼')

  await walkthrough.selectOption(page.locator('#reasonCode'), 'ORDER_MISTAKE')
  await walkthrough.fill(page.locator('#reasonDetail'), '워크스루 취소 신청 사유')
  await walkthrough.shot('취소-사유-입력')

  await walkthrough.click(page.getByTestId('claim-submit'))
  // 완료 조건: 접수 완료 화면.
  await expect(page.getByText('클레임이 접수되었습니다.')).toBeVisible()
  await walkthrough.shot('취소-접수-완료')

  walkthrough.finish()
})
