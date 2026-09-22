import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 1(Track 98): 대시보드에서 클레임 처리 대기를 확인하고 취소 클레임 1건을 승인한다.
 * 완료 조건 = 승인한 클레임의 주문번호가 요청(REQUESTED) 목록에서 사라진다(승인 → Mock 환불 자동 완료 → COMPLETED).
 */
test('관리자 · 클레임 처리 대기 확인 → 취소 클레임 승인', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'claim-cancel-approve', '클레임 대기 확인 → 취소 승인')
  await loginAs(page, 'ADMIN')

  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await expect(page.getByTestId('dashboard-pending')).toBeVisible()
  await walkthrough.shot('대시보드-처리대기-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-claimRequested'))
  await waitScreen(page, 'admin-claim-table')
  await walkthrough.shot('클레임-요청-목록')

  await walkthrough.click(page.getByTestId('claim-tab-CANCEL'))
  const row = page.getByTestId('admin-claim-table').locator('tbody tr')
    .filter({ has: page.getByTestId('row-approve') }).first()
  await expect(row).toBeVisible()
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  await walkthrough.shot('취소-탭-대상-행')

  await walkthrough.click(row.getByTestId('row-approve'))
  await expect(page.getByTestId('admin-claim-approve-dialog')).toBeVisible()
  await walkthrough.shot('승인-확인-다이얼로그')

  await walkthrough.click(page.getByTestId('admin-claim-approve-dialog-ok'))
  await expect(page.getByTestId('admin-claim-approve-dialog')).toBeHidden()
  // 완료 조건: 승인된 클레임은 REQUESTED 목록에서 빠진다.
  await expect(page.getByTestId('admin-claim-table').getByText(orderNo, { exact: true })).toHaveCount(0)
  await walkthrough.shot('승인-후-목록')

  walkthrough.finish()
})
