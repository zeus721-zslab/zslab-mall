import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 셀러 시나리오 6(Track 99): 상품 1건의 판매가를 수정하고 저장한다.
 * 완료 조건 = 저장 성공 토스트('상품을 저장했습니다.').
 *
 * 대시보드 4칸에 상품 화면 진입점이 없어 사이드바로 이동한다. 대상은 seller-product-stop-resume(첫 행)과 겹치지 않도록 목록 마지막 행에서 고른다.
 */
const NEW_BASE_PRICE = 19900

test('셀러 · 상품 가격 수정 → 저장', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'seller', 'product-price-edit', '상품 가격 수정 → 저장')
  await loginAs(page, 'SELLER')

  await walkthrough.goto('/seller')
  await waitScreen(page, 'seller-dashboard')
  walkthrough.note('대시보드 진입점', '없음(상품 화면 칸 미제공 · 재고 임박 칸만 재고 화면으로 이동)')
  await walkthrough.shot('대시보드-상품화면-진입점-없음')

  await walkthrough.click(page.getByTestId('seller-sidebar').locator('a[href="/seller/products"]'))
  await waitScreen(page, 'seller-product-table')
  await walkthrough.shot('상품-목록')

  const row = page.getByTestId('seller-product-table').locator('tbody tr')
    .filter({ hasText: '판매중' }).filter({ has: page.getByTestId('row-edit') }).last()
  await expect(row).toBeVisible()
  const productName = (await row.getByTestId('row-product-name').innerText()).trim()
  const beforePrice = (await row.getByTestId('row-base-price').innerText()).trim()
  walkthrough.note('수정 대상 상품', productName)
  walkthrough.note('수정 전 판매가', beforePrice)

  await walkthrough.click(row.getByTestId('row-edit'))
  await waitScreen(page, 'seller-product-edit')
  await expect(page.getByTestId('field-base-price')).toBeVisible()
  await walkthrough.shot('상품-수정-폼')

  await walkthrough.fill(page.getByTestId('field-base-price').locator('input').first(), String(NEW_BASE_PRICE))
  await walkthrough.shot('판매가-입력')

  await walkthrough.click(page.getByTestId('form-save'))
  // Track 99 FE-61: 판매가가 바뀐 저장은 확인 다이얼로그를 한 번 거친다(전 → 후·변동률).
  await expect(page.getByTestId('seller-price-change-dialog')).toBeVisible()
  walkthrough.note('가격 변경 확인 문구', (await page.getByTestId('price-change-message').innerText()).trim())
  await walkthrough.shot('판매가-변경-확인-다이얼로그')

  await walkthrough.click(page.getByTestId('price-change-ok'))
  // 완료 조건: 저장 성공 토스트.
  await expect(page.getByText('상품을 저장했습니다.')).toBeVisible()
  await walkthrough.shot('저장-완료')

  walkthrough.finish()
})
