import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 셀러 시나리오 5(Track 99): 재고 화면에서 옵션 1건을 입고 처리한다(수량·사유).
 * 완료 조건 = 해당 행의 보유 수량이 입고 수량만큼 늘어난다.
 */
const INBOUND_QUANTITY = 10

test('셀러 · 재고 입고(수량·사유)', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'seller', 'inventory-inbound', '재고 입고(수량·사유)')
  await loginAs(page, 'SELLER')

  await walkthrough.goto('/seller')
  await waitScreen(page, 'seller-dashboard')
  await walkthrough.shot('대시보드-재고임박-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-lowStock'))
  await waitScreen(page, 'seller-inventory-table')
  await walkthrough.shot('재고-목록')

  const row = page.getByTestId('seller-inventory-table').locator('tbody tr')
    .filter({ has: page.getByTestId('row-inbound') }).first()
  await expect(row).toBeVisible()
  const productName = (await row.getByTestId('row-product-name').innerText()).trim()
  const beforeOnHand = Number((await row.getByTestId('row-on-hand').innerText()).replace(/[^0-9]/g, ''))
  walkthrough.note('입고 대상', productName)
  walkthrough.note('입고 전 보유', String(beforeOnHand))

  await walkthrough.click(row.getByTestId('row-inbound'))
  await expect(page.getByTestId('seller-inventory-adjust-dialog')).toBeVisible()
  await walkthrough.shot('입고-다이얼로그')

  await walkthrough.fill(page.getByTestId('adjust-quantity').locator('input').first(), String(INBOUND_QUANTITY))
  await walkthrough.fill(page.getByTestId('adjust-reason').locator('input').first(), '워크스루 입고(발주 입고)')
  await walkthrough.shot('입고-수량-사유-입력')

  await walkthrough.click(page.getByTestId('adjust-dialog-ok'))
  await expect(page.getByTestId('seller-inventory-adjust-dialog')).toBeHidden()
  // 완료 조건: 보유 수량이 입고 수량만큼 늘어난다.
  const target = page.getByTestId('seller-inventory-table').locator('tbody tr').filter({ hasText: productName }).first()
  await expect(target.getByTestId('row-on-hand')).toHaveText((beforeOnHand + INBOUND_QUANTITY).toLocaleString('ko-KR'))
  await walkthrough.shot('입고-반영')

  walkthrough.finish()
})
