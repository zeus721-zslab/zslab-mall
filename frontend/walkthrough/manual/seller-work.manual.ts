import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture, seedRows } from './manual-capture'

/**
 * 셀러 매뉴얼 캡처 — 클레임 조회 · 상품·재고 · 상품 Q&A · 정산 · 통계 · 인박스 · 정산계좌.
 * 다이얼로그·메뉴는 열기만 하고 확인 버튼은 누르지 않는다. 판매 관리 카드의 수동 품절 스위치는 누르는 즉시 저장되므로 영역 표시만 한다.
 * 영역 키는 layers/seller/app/lib/seller-manual/*.ts 콜아웃과 1:1이다.
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'SELLER')
})

test('클레임 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/claims')
  await waitScreen(page, 'seller-claim-table')
  const row = seedRows(page.getByTestId('seller-claim-table').locator('tbody tr')).first()
  await capture.shot('claim-list', {
    type: page.getByTestId('filter-type'),
    status: page.getByTestId('filter-status'),
    statusChip: row.getByTestId('status-chip'),
    open: row.getByTestId('row-open'),
  })
})

test('클레임 상세', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/claims')
  await waitScreen(page, 'seller-claim-table')
  await seedRows(page.getByTestId('seller-claim-table').locator('tbody tr')).first().getByTestId('row-open').click()
  await waitScreen(page, 'claim-detail-info')
  await page.getByTestId('claim-detail-timeline').scrollIntoViewIfNeeded()
  await capture.shot('claim-detail', {
    status: page.getByTestId('claim-detail-status'),
    timeline: page.getByTestId('claim-detail-timeline'),
  })
})

test('상품 목록과 판매 관리 메뉴', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products?status=SALE')
  await waitScreen(page, 'seller-product-table')
  const row = seedRows(page.getByTestId('seller-product-table').locator('tbody tr')).filter({ has: page.getByTestId('row-menu') }).first()
  await row.getByTestId('row-menu').click()
  await expect(page.getByTestId('row-sale-action')).toBeVisible()
  await capture.shot('product-list', {
    create: page.getByTestId('product-new'),
    status: page.getByTestId('filter-status'),
    edit: row.getByTestId('row-edit'),
    saleAction: page.getByTestId('row-sale-action'),
  })
})

test('상품 수정 — 판매 관리 카드', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products?status=SALE')
  await waitScreen(page, 'seller-product-table')
  await seedRows(page.getByTestId('seller-product-table').locator('tbody tr')).first().getByTestId('row-edit').click()
  await waitScreen(page, 'seller-sale-status-card')
  await capture.shot('product-sale-card', {
    status: page.getByTestId('sale-card-status-chip'),
    action: page.getByTestId('sale-card-action'),
    soldOut: page.getByTestId('sale-card-soldout'),
  })
})

test('상품 등록 폼', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products/new')
  await waitScreen(page, 'seller-product-form')
  await capture.shot('product-new', {
    category: page.getByTestId('field-category'),
    price: page.getByTestId('field-base-price'),
    name: page.getByTestId('field-name'),
  })
})

test('재고 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products/inventory')
  await waitScreen(page, 'seller-inventory-table')
  const row = seedRows(page.getByTestId('seller-inventory-table').locator('tbody tr')).first()
  await capture.shot('inventory-list', {
    keyword: page.getByTestId('filter-keyword'),
    available: row.getByTestId('row-available').or(row.getByTestId('row-available-chip')),
    inbound: row.getByTestId('row-inbound'),
    outbound: row.getByTestId('row-outbound'),
  })
})

test('입고 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products/inventory')
  await waitScreen(page, 'seller-inventory-table')
  await seedRows(page.getByTestId('seller-inventory-table').locator('tbody tr')).first().getByTestId('row-inbound').click()
  const dialog = page.getByTestId('seller-inventory-adjust-dialog')
  await expect(dialog).toBeVisible()
  await capture.shot('inventory-adjust', {
    item: dialog.getByTestId('adjust-item'),
    quantity: dialog.getByTestId('adjust-quantity'),
    reason: dialog.getByTestId('adjust-reason'),
    confirm: dialog.getByTestId('adjust-dialog-ok'),
  }, { focus: dialog })
  await dialog.getByTestId('adjust-dialog-close').click()
})

test('상품 Q&A 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products/questions')
  await waitScreen(page, 'seller-question-table')
  const row = seedRows(page.getByTestId('seller-question-table').locator('tbody tr')).first()
  await capture.shot('question-list', {
    answered: page.getByTestId('filter-answered'),
    unanswered: row.getByTestId('row-unanswered'),
    answer: row.getByTestId('row-answer-open'),
  })
})

test('Q&A 답변 다이얼로그(답안 초안)', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/products/questions')
  await waitScreen(page, 'seller-question-table')
  await seedRows(page.getByTestId('seller-question-table').locator('tbody tr')).first().getByTestId('row-answer-open').click()
  const dialog = page.getByTestId('seller-question-answer-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByTestId('answer-draft-loading')).toHaveCount(0)
  await capture.shot('question-answer', {
    question: dialog.getByTestId('answer-question'),
    draft: dialog.getByTestId('answer-draft-box'),
    content: dialog.getByTestId('answer-content'),
    confirm: dialog.getByTestId('answer-dialog-ok'),
  }, { focus: dialog })
  await dialog.getByTestId('answer-dialog-close').click()
})

test('정산 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/settlements')
  await waitScreen(page, 'seller-settlement-table')
  const row = page.getByTestId('seller-settlement-table').locator('tbody tr').first()
  await capture.shot('settlement-list', {
    pending: page.getByTestId('seller-settlement-pending-notice'),
    net: row.getByTestId('row-net'),
    status: row.getByTestId('row-status'),
    open: row.getByTestId('row-open'),
  })
})

test('정산 상세', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/settlements')
  await waitScreen(page, 'seller-settlement-table')
  await page.getByTestId('seller-settlement-table').getByTestId('row-open').first().click()
  await waitScreen(page, 'settlement-summary')
  await capture.shot('settlement-detail', {
    status: page.getByTestId('settlement-status'),
    net: page.getByTestId('settlement-net'),
    bank: page.getByTestId('settlement-bank'),
  })
})

test('매출 통계', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/stats/sales?preset=3m&unit=MONTH')
  await waitScreen(page, 'sales-summary')
  await capture.shot('stats-sales', {
    tabs: page.getByTestId('seller-stats-tabs'),
    period: page.getByTestId('seller-period-picker'),
    summary: page.getByTestId('sales-summary'),
  })
})

test('인박스', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/inbox')
  await waitScreen(page, 'inbox-items')
  await waitScreen(page, 'inbox-detail-snooze')
  await capture.shot('inbox-overview', {
    tabs: page.getByTestId('inbox-tab-TODAY'),
    chips: page.getByTestId('inbox-type-chips'),
    origin: page.getByTestId('inbox-detail-open-origin'),
    snooze: page.getByTestId('inbox-detail-snooze'),
  })
})

test('인박스 보류 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/inbox')
  await waitScreen(page, 'inbox-detail-snooze')
  await page.getByTestId('inbox-detail-snooze').click()
  const dialog = page.getByTestId('inbox-snooze-dialog')
  await expect(dialog).toBeVisible()
  await capture.shot('inbox-snooze', {
    reason: dialog.getByTestId('inbox-snooze-reason-0'),
    preset: dialog.getByTestId('inbox-snooze-preset-TOMORROW_MORNING'),
    submit: dialog.getByTestId('inbox-snooze-submit'),
  }, { focus: dialog })
  await dialog.getByTestId('inbox-snooze-cancel').click()
})

test('정산계좌', async ({ page }) => {
  const capture = new ManualCapture(page, 'seller')
  await page.goto('/seller/settings/bank-account')
  await waitScreen(page, 'seller-bank-account-list-card')
  // 등록 폼은 대표(OWNER)에게만 보인다(bank-account.vue:28,139) — 로그인 셀러 역할에 따라 폼 또는 조회 전용 안내를 찍는다.
  const register = page.getByTestId('seller-bank-account-form-card').or(page.getByTestId('seller-bank-account-readonly-notice'))
  await expect(register).toBeVisible()
  await capture.shot('bank-account', {
    list: page.getByTestId('seller-bank-account-list-card'),
    register,
  })
})
