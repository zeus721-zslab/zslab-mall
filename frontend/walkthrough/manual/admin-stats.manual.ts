import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 통계·불일치 점검. 영역 키는 layers/admin/app/lib/admin-manual/stats-reconciliation.ts와 1:1이다.
 * 불일치는 시드로 만들 수 없고(정기 점검 결과에만 생김) 화면에 보이는 그대로(빈 상태 포함) 찍는다.
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

test('매출 통계', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/stats/sales?preset=3m&unit=MONTH')
  await waitScreen(page, 'sales-summary')
  await capture.shot('stats-sales', {
    tabs: page.getByTestId('stats-tabs'),
    period: page.getByTestId('admin-period-picker'),
    summary: page.getByTestId('sales-summary'),
  })
})

test('불일치 점검', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/orders/reconciliation')
  await waitScreen(page, 'reconciliation-total')
  const issue = page.getByTestId('reconciliation-resolve').first()
  const empty = page.getByTestId('reconciliation-empty')
  await expect(issue.or(empty)).toBeVisible()
  await capture.shot('reconciliation', {
    status: page.getByTestId('reconciliation-filter-status'),
    type: page.getByTestId('reconciliation-filter-type'),
    result: (await empty.isVisible()) ? empty : issue,
  })
})
