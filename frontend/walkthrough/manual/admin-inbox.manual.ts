import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 운영 인박스(전체 보기 · 보류 · 셀러 지연 독촉). 보류·독촉 확인 버튼은 누르지 않는다.
 * 영역 키는 layers/admin/app/lib/admin-manual/inbox.ts와 1:1이다. 데이터 전제: 셀러 지연(SELLER_DELAY) 항목 1건 이상.
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

test('인박스 전체', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inbox')
  await waitScreen(page, 'inbox-items')
  await waitScreen(page, 'inbox-detail-snooze')
  await capture.shot('inbox-overview', {
    tabs: page.getByTestId('inbox-tab-TODAY'),
    chips: page.getByTestId('inbox-type-chips'),
    origin: page.getByTestId('inbox-detail-open-origin'),
    snooze: page.getByTestId('inbox-detail-snooze'),
  })
})

test('보류 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inbox')
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

test('셀러 지연 독촉 선택', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inbox?type=SELLER_DELAY')
  await waitScreen(page, 'inbox-items')
  const checks = page.getByTestId('inbox-bulk-check').locator('input:not([disabled])')
  await expect(checks.first()).toBeVisible()
  const count = Math.min(await checks.count(), 2)
  for (let index = 0; index < count; index += 1) await checks.nth(index).check()
  await waitScreen(page, 'inbox-nudge-bar')
  await capture.shot('inbox-nudge', {
    check: page.getByTestId('inbox-bulk-check').first(),
    count: page.getByTestId('inbox-nudge-count'),
    send: page.getByTestId('inbox-nudge-send'),
  })
})
