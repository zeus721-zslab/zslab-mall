import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 6(Track 99): 대시보드에서 클레임 요청으로 들어가 취소 클레임 1건을 거부한다(사유 코드 선택·메모 입력).
 * 완료 조건 = 거부한 클레임의 주문번호가 요청(REQUESTED) 목록에서 사라진다.
 */
test('관리자 · 취소 클레임 거부(사유 코드·메모)', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'claim-cancel-reject', '취소 클레임 거부(사유 코드)')
  await loginAs(page, 'ADMIN')

  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await walkthrough.shot('대시보드-클레임요청-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-claimRequested'))
  await waitScreen(page, 'admin-claim-table')
  await walkthrough.click(page.getByTestId('claim-tab-CANCEL'))
  const row = page.getByTestId('admin-claim-table').locator('tbody tr')
    .filter({ has: page.getByTestId('row-reject') }).first()
  await expect(row).toBeVisible()
  const orderNo = (await row.getByTestId('row-order-no').innerText()).trim()
  walkthrough.note('거부 대상 주문번호', orderNo)
  await walkthrough.shot('취소-탭-대상-행')

  await walkthrough.click(row.getByTestId('row-reject'))
  await expect(page.getByTestId('admin-claim-reject-dialog')).toBeVisible()
  await walkthrough.shot('거부-다이얼로그')

  await walkthrough.select(page.getByTestId('reject-reason'), '이미 발송됨')
  await walkthrough.fill(page.getByTestId('reject-memo').locator('textarea').first(), '워크스루 거부 사유(이미 발송됨)')
  await walkthrough.shot('거부-사유-입력')

  await walkthrough.click(page.getByTestId('reject-dialog-ok'))
  await expect(page.getByTestId('admin-claim-reject-dialog')).toBeHidden()
  // 완료 조건: 거부된 클레임은 REQUESTED 목록에서 빠진다.
  await expect(page.getByTestId('admin-claim-table').getByText(orderNo, { exact: true })).toHaveCount(0)
  await walkthrough.shot('거부-후-목록')

  walkthrough.finish()
})
