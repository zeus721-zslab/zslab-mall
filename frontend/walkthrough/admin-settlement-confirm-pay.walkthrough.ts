import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 2(Track 98): 정산 대기 1건을 정상처리(CONFIRMED)하고 지급완료(PAID)까지 처리한다.
 * 완료 조건 = 정산 상세 상태 칩이 '지급완료'.
 */
test('관리자 · 정산 대기 → 정상처리 → 지급완료', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'settlement-confirm-pay', '정산 대기 → 확정 → 지급')
  await loginAs(page, 'ADMIN')

  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await walkthrough.shot('대시보드-정산대기-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-settlementPending'))
  await waitScreen(page, 'admin-settlement-table')
  await walkthrough.shot('정산-대기-목록')

  const row = page.getByTestId('admin-settlement-table').locator('tbody tr')
    .filter({ hasText: '대기' }).first()
  await expect(row).toBeVisible()
  await walkthrough.click(row.getByTestId('row-open'))
  await waitScreen(page, 'settlement-summary')
  await expect(page.getByTestId('settlement-status')).toHaveText('대기')
  await walkthrough.shot('정산-상세-대기')

  await walkthrough.click(page.getByTestId('action-confirm'))
  await expect(page.getByTestId('settlement-confirm-dialog')).toBeVisible()
  await walkthrough.shot('정상처리-확인-다이얼로그')
  await walkthrough.click(page.getByTestId('settlement-confirm-dialog-ok'))
  await expect(page.getByTestId('settlement-status')).toHaveText('확정')
  await walkthrough.shot('정상처리-완료')

  await walkthrough.click(page.getByTestId('action-pay'))
  await expect(page.getByTestId('settlement-pay-dialog')).toBeVisible()
  await walkthrough.shot('지급완료-확인-다이얼로그')
  await walkthrough.click(page.getByTestId('settlement-pay-dialog-ok'))
  // 완료 조건: 상태 칩 지급완료.
  await expect(page.getByTestId('settlement-status')).toHaveText('지급완료')
  await walkthrough.shot('지급완료-반영')

  walkthrough.finish()
})
