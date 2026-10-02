import { expect, test } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { DEV_RESIDUE_PATTERN, ManualCapture, seedRows } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 클레임 처리. 화면을 열고 다이얼로그를 띄우기만 하며 확인 버튼은 누르지 않는다(상태 전이 없음).
 * 영역 키는 layers/admin/app/lib/admin-manual/claim.ts 콜아웃의 region과 1:1이다.
 *
 * 데이터 전제(seed.py orders 단계): 오늘 탭 클레임 접수 · 승인 제안 1건 이상 · 후속 처리(회수 확인 또는 검수 대기) 반품·교환 1건 이상.
 * 시드는 회수 송장을 만들지 않으므로 후속 처리·검수 캡처 전에 회수 대기 반품 1건에 "회수 송장 대행 등록"을 한 번 해 둔다(C8 P2 0번).
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

test('클레임 접수 목록(인박스)', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inbox?type=CLAIM_REQUESTED')
  await waitScreen(page, 'inbox-items')
  const firstItem = page.getByTestId('inbox-item').filter({ has: page.getByTestId('inbox-item-suggestion') }).first()
  await expect(firstItem).toBeVisible()
  // PC 2단 화면은 첫 항목 상세를 자동으로 연다 — 다음 단계(상세) 캡처와 겹치지 않게 목록만 자른다.
  await capture.shot('claim-inbox', {
    typeChip: page.getByTestId('inbox-type-chip-CLAIM_REQUESTED'),
    todayTab: page.getByTestId('inbox-tab-TODAY'),
    suggestion: firstItem.getByTestId('inbox-item-suggestion'),
    deadline: firstItem.getByTestId('inbox-item-deadline'),
  }, { focus: page.getByTestId('inbox-list') })
})

test('처리 제안 확인(인박스 상세)', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inbox?type=CLAIM_REQUESTED')
  await waitScreen(page, 'inbox-items')
  // 상세 사유는 목록에 보이지 않으므로 열어 보고, 시드가 아닌 검증·워크스루 메모가 든 클레임은 건너뛴다(DEV_RESIDUE_PATTERN).
  // 로컬 시드의 접수 클레임은 교환 1건(검토 필요)이라 제안 종류는 가리지 않는다.
  const candidates = page.getByTestId('inbox-item').filter({ has: page.getByTestId('inbox-item-suggestion') })
  const count = await candidates.count()
  let found = false
  for (let index = 0; index < count && !found; index += 1) {
    await candidates.nth(index).click()
    await expect(page.getByTestId('inbox-claim-suggestion')).toBeVisible()
    found = !DEV_RESIDUE_PATTERN.test(await page.getByTestId('inbox-claim-panel').innerText())
  }
  expect(found, '시드 데이터의 접수 클레임이 없습니다').toBe(true)
  await capture.shot('claim-suggestion', {
    suggestion: page.getByTestId('inbox-claim-suggestion'),
    reason: page.getByTestId('inbox-claim-reason'),
    approve: page.getByTestId('inbox-claim-approve'),
    reject: page.getByTestId('inbox-claim-reject'),
  }, { focus: page.getByTestId('inbox-detail') })
})

test('승인 제안 일괄 선택(인박스)', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/inbox?type=CLAIM_REQUESTED')
  await waitScreen(page, 'inbox-items')
  // 일괄 승인은 "클레임 접수 + 승인 제안" 행만 고를 수 있다(admin-inbox-view.ts:55-58) — 체크가 활성인 행만 고른다.
  const checks = page.getByTestId('inbox-bulk-check').locator('input:not([disabled])')
  await expect(checks.first()).toBeVisible()
  const count = Math.min(await checks.count(), 2)
  for (let index = 0; index < count; index += 1) await checks.nth(index).check()
  await waitScreen(page, 'inbox-bulk-bar')
  await capture.shot('claim-bulk', {
    check: page.getByTestId('inbox-bulk-check').first(),
    count: page.getByTestId('inbox-bulk-count'),
    approve: page.getByTestId('inbox-bulk-approve'),
    clear: page.getByTestId('inbox-bulk-clear'),
  })
})

test('후속 처리 목록(클레임 화면)', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/orders/claims?action=FOLLOWUP')
  await waitScreen(page, 'admin-claim-table')
  const row = seedRows(page.getByTestId('admin-claim-table').locator('tbody tr')).filter({ has: page.getByTestId('row-inspect') }).first()
  await expect(row).toBeVisible()
  await capture.shot('claim-followup', {
    tabs: page.getByTestId('claim-type-tabs'),
    actionFilter: page.getByTestId('filter-action'),
    confirmPickup: row.getByTestId('row-confirm-pickup'),
    inspect: row.getByTestId('row-inspect'),
  })
})

test('검수 다이얼로그', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/orders/claims?action=FOLLOWUP')
  await waitScreen(page, 'admin-claim-table')
  const row = seedRows(page.getByTestId('admin-claim-table').locator('tbody tr')).filter({ has: page.getByTestId('row-inspect') }).first()
  await row.getByTestId('row-inspect').click()
  await waitScreen(page, 'admin-claim-inspect-dialog')
  // 합격을 골라야 재입고 선택이 나타난다. 확인 버튼은 누르지 않는다.
  await page.getByTestId('inspect-result-PASS').click()
  await expect(page.getByTestId('inspect-restock')).toBeVisible()
  await capture.shot('claim-inspect', {
    pickup: page.getByTestId('inspect-pickup-check'),
    result: page.getByTestId('inspect-result'),
    restock: page.getByTestId('inspect-restock'),
    confirm: page.getByTestId('inspect-dialog-ok'),
  }, { focus: page.getByTestId('admin-claim-inspect-dialog') })
  await page.getByTestId('inspect-dialog-close').click()
})
