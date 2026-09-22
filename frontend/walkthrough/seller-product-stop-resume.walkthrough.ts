import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 셀러 시나리오 2(Track 98): 판매중 상품을 판매중지했다가 다시 재판매한다(셀프 전환·Track 96-5).
 * 완료 조건 = 상태 칩이 판매중지 → 판매중으로 되돌아온다.
 */
test('셀러 · 상품 판매중지 → 재판매', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'seller', 'product-stop-resume', '상품 판매중지 → 재판매')
  await loginAs(page, 'SELLER')

  await walkthrough.goto('/seller/products')
  await waitScreen(page, 'seller-product-table')
  await walkthrough.shot('상품-목록')

  const row = page.getByTestId('seller-product-table').locator('tbody tr')
    .filter({ hasText: '판매중' }).filter({ has: page.getByTestId('row-menu') }).first()
  await expect(row).toBeVisible()
  const productName = (await row.getByTestId('row-product-name').innerText()).trim()

  await walkthrough.click(row.getByTestId('row-menu'))
  await walkthrough.shot('판매관리-메뉴')
  await walkthrough.click(page.getByTestId('row-sale-action'))
  await expect(page.getByTestId('seller-sale-status-dialog')).toBeVisible()
  await walkthrough.shot('판매중지-확인-다이얼로그')
  await walkthrough.click(page.getByTestId('sale-status-ok'))
  await expect(page.getByTestId('seller-sale-status-dialog')).toBeHidden()

  const target = page.getByTestId('seller-product-table').locator('tbody tr').filter({ hasText: productName }).first()
  await expect(target.getByTestId('status-chip')).toHaveText('판매중지')
  await walkthrough.shot('판매중지-반영')

  await walkthrough.click(target.getByTestId('row-menu'))
  await walkthrough.click(page.getByTestId('row-sale-action'))
  await expect(page.getByTestId('seller-sale-status-dialog')).toBeVisible()
  await walkthrough.click(page.getByTestId('sale-status-ok'))
  await expect(page.getByTestId('seller-sale-status-dialog')).toBeHidden()
  // 완료 조건: 판매중 복귀.
  await expect(target.getByTestId('status-chip')).toHaveText('판매중')
  await walkthrough.shot('재판매-반영')

  walkthrough.finish()
})
