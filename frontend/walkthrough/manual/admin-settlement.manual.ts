import { expect, test, type Page } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 정산. 확정·지급 버튼은 누르지 않는다. 영역 키는 layers/admin/app/lib/admin-manual/settlement.ts와 1:1이다.
 * 데이터 전제(seed.py settlement): 2026-08 확정 대기(PENDING) · 2026-07 확정(CONFIRMED) 정산.
 */
const PENDING_MONTH = { year: 2026, month: 8 }
const CONFIRMED_MONTH = { year: 2026, month: 7 }

test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

async function openSettlement(page: Page, period: { year: number; month: number }, status: string): Promise<void> {
  await page.goto(`/admin/settlements?year=${period.year}&month=${period.month}&status=${status}`)
  await waitScreen(page, 'admin-settlement-table')
  await page.getByTestId('admin-settlement-table').getByTestId('row-open').first().click()
  await waitScreen(page, 'settlement-summary')
}

test('정산 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto(`/admin/settlements?year=${PENDING_MONTH.year}&month=${PENDING_MONTH.month}`)
  await waitScreen(page, 'admin-settlement-table')
  await capture.shot('settlement-list', {
    create: page.getByTestId('settlement-create'),
    status: page.getByTestId('filter-status'),
    totals: page.getByTestId('admin-settlement-totals'),
    open: page.getByTestId('admin-settlement-table').getByTestId('row-open').first(),
  })
})

test('확정 대기 정산 상세', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await openSettlement(page, PENDING_MONTH, 'PENDING')
  await expect(page.getByTestId('action-confirm')).toBeVisible()
  await capture.shot('settlement-pending', {
    status: page.getByTestId('settlement-status'),
    confirm: page.getByTestId('action-confirm'),
    regenerate: page.getByTestId('action-regenerate'),
    net: page.getByTestId('settlement-net'),
  })
})

test('확정 정산 상세(지급완료)', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await openSettlement(page, CONFIRMED_MONTH, 'CONFIRMED')
  await expect(page.getByTestId('action-pay')).toBeVisible()
  await capture.shot('settlement-confirmed', {
    status: page.getByTestId('settlement-status'),
    pay: page.getByTestId('action-pay'),
    bank: page.getByTestId('settlement-bank'),
  })
})
