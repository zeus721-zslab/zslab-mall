import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture, seedRows } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 상품·재고. 체크·메뉴 열기만 한다. 수동 품절 스위치 · 메뉴의 판매 상태 항목 · 카테고리 위/아래 화살표는
 * 누르는 즉시 저장되므로(admin/products/index.vue:112-144 · categories.vue:90-96) 영역 표시만 하고 누르지 않는다.
 * 영역 키는 layers/admin/app/lib/admin-manual/product.ts와 1:1이다.
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

test('상품 목록과 일괄 처리', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products')
  await waitScreen(page, 'admin-product-table')
  const rows = seedRows(page.getByTestId('admin-product-table').locator('tbody tr'))
  for (let index = 0; index < 2; index += 1) await rows.nth(index).locator('input[type="checkbox"]').first().check()
  await waitScreen(page, 'admin-bulk-bar')
  await capture.shot('product-list', {
    filters: page.getByTestId('admin-product-filters'),
    bulkBar: page.getByTestId('admin-bulk-bar'),
    statusApply: page.getByTestId('bulk-status-apply'),
    soldOut: rows.first().getByTestId('soldout-toggle'),
  })
})

test('상품 관리 메뉴', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products?status=SALE')
  await waitScreen(page, 'admin-product-table')
  const row = seedRows(page.getByTestId('admin-product-table').locator('tbody tr')).first()
  await row.getByTestId('row-menu').click()
  await expect(page.getByTestId('row-delete')).toBeVisible()
  await capture.shot('product-menu', {
    edit: row.getByTestId('row-edit'),
    stop: page.getByTestId('row-status-STOPPED'),
    delete: page.getByTestId('row-delete'),
  })
})

test('상품 등록 폼', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products/new')
  await waitScreen(page, 'admin-product-form')
  await capture.shot('product-new', {
    seller: page.getByTestId('field-seller'),
    category: page.getByTestId('field-category'),
    name: page.getByTestId('field-name'),
    price: page.getByTestId('field-base-price'),
  })
})

test('카테고리', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/products/categories')
  await waitScreen(page, 'admin-category-table')
  const row = page.getByTestId('admin-category-table').locator('tbody tr').first()
  await capture.shot('product-category', {
    create: page.getByTestId('go-create'),
    order: row.getByTestId('row-move-down'),
    edit: row.getByTestId('row-edit'),
    delete: row.getByTestId('row-delete'),
  })
})
