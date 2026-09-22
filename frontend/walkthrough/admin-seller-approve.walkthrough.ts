import { expect, test } from '@playwright/test'
import { loginAs } from '../e2e/helpers/login'
import { Walkthrough, waitScreen } from './helpers/walkthrough'

/**
 * 관리자 시나리오 3(Track 98): 승인 대기(PENDING) 셀러를 활성(ACTIVE)으로 전이한다.
 * 완료 조건 = 셀러 상세 상태 칩이 '활성'.
 */
test('관리자 · 셀러 승인 대기 → 입점 승인(활성)', async ({ page }) => {
  const walkthrough = new Walkthrough(page, 'admin', 'seller-approve', '셀러 승인 대기 → 입점 승인')
  await loginAs(page, 'ADMIN')

  await walkthrough.goto('/admin')
  await waitScreen(page, 'admin-dashboard')
  await walkthrough.shot('대시보드-셀러승인대기-확인')

  await walkthrough.click(page.getByTestId('dashboard-pending-sellerPending'))
  await waitScreen(page, 'admin-seller-table')
  await walkthrough.shot('셀러-승인대기-목록')

  const row = page.getByTestId('admin-seller-table').locator('tbody tr')
    .filter({ hasText: '승인 대기' }).first()
  await expect(row).toBeVisible()
  await walkthrough.click(row)
  await waitScreen(page, 'seller-info')
  await expect(page.getByTestId('seller-status')).toHaveText('승인 대기')
  await walkthrough.shot('셀러-상세-승인대기')

  await walkthrough.click(page.getByTestId('action-status-ACTIVE'))
  await expect(page.getByTestId('admin-seller-status-dialog')).toBeVisible()
  await walkthrough.fill(page.getByTestId('seller-status-reason').locator('textarea').first(), '입점 서류 확인 완료(워크스루)')
  await walkthrough.shot('상태-전이-다이얼로그')

  await walkthrough.click(page.getByTestId('seller-status-ok'))
  await expect(page.getByTestId('admin-seller-status-dialog')).toBeHidden()
  // 완료 조건: 상태 칩 활성.
  await expect(page.getByTestId('seller-status')).toHaveText('활성')
  await walkthrough.shot('승인-후-상세')

  walkthrough.finish()
})
