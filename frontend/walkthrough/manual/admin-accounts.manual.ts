import { expect, test, type Page } from '@playwright/test'
import { loginAs } from '../../e2e/helpers/login'
import { waitScreen } from '../helpers/walkthrough'
import { ManualCapture, seedAccountRows } from './manual-capture'

/**
 * 관리자 매뉴얼 캡처 — 회원·셀러·관리자 계정. 버튼은 영역 표시만 하고 누르지 않는다(탈퇴·임시 비밀번호·상태 전이 없음).
 * 영역 키는 layers/admin/app/lib/admin-manual/account.ts와 1:1이다.
 * 탈퇴회원 화면은 시드로 채울 수 없어(seed.py·prepare.py에 탈퇴 처리 없음) 화면에 보이는 그대로(빈 상태 포함) 찍는다.
 */
test.beforeEach(async ({ page }) => {
  await loginAs(page, 'ADMIN')
})

test('회원 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/members')
  await waitScreen(page, 'admin-member-table')
  const row = seedAccountRows(page.getByTestId('admin-member-table').locator('tbody tr')).first()
  await capture.shot('member-list', {
    keyword: page.getByTestId('filter-keyword'),
    sort: page.getByTestId('filter-sort'),
    open: row.getByTestId('row-open'),
  })
})

test('회원 상세', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/members')
  await waitScreen(page, 'admin-member-table')
  await seedAccountRows(page.getByTestId('admin-member-table').locator('tbody tr')).first().getByTestId('row-open').click()
  await waitScreen(page, 'member-info')
  await capture.shot('member-detail', {
    edit: page.getByTestId('action-edit'),
    resetPassword: page.getByTestId('action-reset-password'),
    withdraw: page.getByTestId('action-withdraw'),
    grade: page.getByTestId('action-grade'),
  })
})

test('탈퇴회원', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/members/withdrawn')
  const empty = page.getByTestId('admin-member-empty')
  const table = page.getByTestId('admin-member-table')
  await expect(empty.or(table)).toBeVisible()
  await capture.shot('member-withdrawn', {
    keyword: page.getByTestId('filter-keyword'),
    list: empty.or(table),
  })
})

test('셀러 목록', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/members/sellers')
  await waitScreen(page, 'admin-seller-table')
  const row = seedAccountRows(page.getByTestId('admin-seller-table').locator('tbody tr')).first()
  await capture.shot('seller-list', {
    provision: page.getByTestId('seller-provision-open'),
    status: page.getByTestId('filter-status'),
    rowStatus: row.getByTestId('row-status'),
  })
})

/** 시드 셀러 상세로 간다. 행 가운데에는 상품 수 링크가 있어 첫 칸(상호)을 눌러 행 클릭으로 들어간다. */
async function openSeedSeller(page: Page): Promise<void> {
  await page.goto('/admin/members/sellers?status=ACTIVE')
  await waitScreen(page, 'admin-seller-table')
  await seedAccountRows(page.getByTestId('admin-seller-table').locator('tbody tr')).first().locator('td').first().click()
  await waitScreen(page, 'seller-info')
}

test('셀러 상세 — 상태 전이', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await openSeedSeller(page)
  await capture.shot('seller-detail', {
    status: page.getByTestId('seller-status'),
    edit: page.getByTestId('action-edit'),
    suspend: page.getByTestId('action-status-SUSPENDED'),
    terminate: page.getByTestId('action-status-TERMINATED'),
  })
})

test('셀러 상세 — 정산계좌·구성원', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await openSeedSeller(page)
  await page.getByTestId('seller-members').scrollIntoViewIfNeeded()
  await capture.shot('seller-cards', {
    bank: page.getByTestId('seller-bank-account'),
    members: page.getByTestId('seller-members'),
    memberAdd: page.getByTestId('seller-member-add'),
  })
})

test('운영자', async ({ page }) => {
  const capture = new ManualCapture(page, 'admin')
  await page.goto('/admin/members/admins')
  await waitScreen(page, 'admin-operator-table')
  await capture.shot('admin-operators', {
    provision: page.getByTestId('go-provision'),
    role: page.getByTestId('filter-role'),
    revoke: page.getByTestId('admin-operator-table').getByTestId('row-revoke').first(),
  })
})
