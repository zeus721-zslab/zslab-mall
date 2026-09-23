import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 운영자 관리(FE-39·Track 89-E) E2E 스모크. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), /admin/me·운영자 목록·회원 검색은
 * page.route로 mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(진입 → 역할 배지·겸직 chip·나 chip → 자기 행 회수 비활성 툴팁 → 타 행 회수 다이얼로그
 * (마지막 SUPER_ADMIN 선택지 비활성·사유 비면 확인 비활성) → 등록 다이얼로그(회원 검색 → 선택 → 확인 문구)까지. POST·DELETE는 호출하지 않는다).
 */
const ME_ID = 'usr_E2E00000000000000000ME01'
const OP_ID = 'usr_E2E00000000000000000OP02'
const ME = { userPublicId: ME_ID, name: 'E2E슈퍼', email: 'super@e2e.invalid', roles: ['SUPER_ADMIN'], superAdmin: true }
const ROWS = [
  { userPublicId: ME_ID, name: 'E2E슈퍼', email: 'super@e2e.invalid', roles: ['SUPER_ADMIN'], hasBuyerRole: false, createdAt: '2026-09-10T10:00:00' },
  { userPublicId: OP_ID, name: 'E2E운영', email: 'op@e2e.invalid', roles: ['SUPER_ADMIN', 'ADMIN_OPERATOR'], hasBuyerRole: true, createdAt: '2026-09-01T09:00:00' },
]
const MEMBER = { publicId: 'usr_E2E00000000000000000BY03', name: 'E2E회원', email: 'buyer@e2e.invalid', phone: '010-1111-2222', gradeCode: 'SILVER', createdAt: '2026-09-05T09:00:00' }

interface Captured { listQueries: URLSearchParams[]; memberQueries: URLSearchParams[]; writes: string[] }

async function mockOperatorApi(page: Page): Promise<Captured> {
  const captured: Captured = { listQueries: [], memberQueries: [], writes: [] }
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/me'), (route) => route.fulfill({ json: ME }))
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/admin-operators'), (route) => {
    if (route.request().method() !== 'GET') {
      captured.writes.push(`${route.request().method()} ${route.request().url()}`)
      return route.fulfill({ status: 201, json: { userPublicId: MEMBER.publicId } })
    }
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const role = query.get('role')
    const items = role ? ROWS.filter((row) => row.roles.includes(role)) : ROWS
    return route.fulfill({ json: { items, page: 0, size: 20, totalCount: items.length, hasNext: false } })
  })
  await page.route((url) => /\/api\/v1\/admin\/users\/[^/]+\/roles\/[^/]+$/.test(url.pathname), (route) => {
    captured.writes.push(`${route.request().method()} ${route.request().url()}`)
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/members'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.memberQueries.push(query)
    const items = (query.get('keyword') ?? '').includes('E2E') ? [MEMBER] : []
    return route.fulfill({ json: { items, page: 0, size: 10, totalCount: items.length, hasNext: false } })
  })
  return captured
}

test.describe('관리자 운영자 관리(FE-39)', () => {
  test('① 진입(2행·역할 배지·겸직·나 chip) → 자기 행 회수 비활성 툴팁 → 타 행 회수 다이얼로그(선택지·사유 필수·문구) → 역할 필터 → 등록 다이얼로그(검색·선택·문구) (POST·DELETE 0)', async ({ page }) => {
    const captured = await mockOperatorApi(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/members/admins')

    // 목록: 2행·첫 행은 나(chip)·둘째 행 역할 배지 2개 + 겸직 chip
    await expect(page.getByTestId('row-name')).toHaveCount(2)
    await expect(page.getByTestId('row-self-chip')).toHaveCount(1)
    const secondRoles = page.getByTestId('row-roles').nth(1)
    await expect(secondRoles.getByTestId('row-role-chip')).toHaveCount(2)
    await expect(secondRoles.getByTestId('row-role-chip').first()).toHaveText('슈퍼 관리자')
    await expect(secondRoles.getByTestId('row-buyer-chip')).toHaveText('일반회원 겸직')
    await expect(page.getByTestId('row-status').first()).toHaveText('활성')
    expect(captured.listQueries[0]?.get('status')).toBe('ACTIVE')

    // 자기 행: 회수 비활성 + 툴팁
    await expect(page.getByTestId('row-revoke').first()).toBeDisabled()
    await page.getByTestId('row-revoke-wrapper').first().hover()
    await expect(page.getByTestId('row-revoke-blocked').first()).toContainText('자기 자신의 역할은 회수할 수 없습니다')

    // 타 행: 회수 다이얼로그 — SUPER_ADMIN 2명이라 슈퍼 선택 가능·기본 선택 슈퍼·재부여 불가 문구·사유 비면 확인 비활성
    await expect(page.getByTestId('row-revoke').nth(1)).toBeEnabled()
    await page.getByTestId('row-revoke').nth(1).click()
    const revokeDialog = page.getByTestId('admin-operator-revoke-dialog')
    await expect(revokeDialog).toBeVisible()
    await expect(revokeDialog.getByTestId('revoke-role-SUPER_ADMIN').locator('input')).toBeEnabled()
    await expect(revokeDialog.getByTestId('revoke-message')).toContainText('되돌릴 수 없습니다.') // Track 102 FE-64 가역성 규약
    await expect(revokeDialog.getByTestId('revoke-message')).toContainText('남은 역할(운영 관리자)은 유지됩니다')
    await expect(revokeDialog.getByTestId('revoke-dialog-ok')).toBeDisabled()
    await revokeDialog.getByTestId('revoke-role-ADMIN_OPERATOR').locator('label').click()
    await expect(revokeDialog.getByTestId('revoke-message')).toContainText('남은 역할(슈퍼 관리자)은 유지되어')
    await revokeDialog.getByTestId('revoke-reason').locator('textarea').first().fill('E2E 사유')
    await expect(revokeDialog.getByTestId('revoke-dialog-ok')).toBeEnabled()
    await revokeDialog.getByTestId('revoke-dialog-close').click()
    await expect(revokeDialog).toBeHidden()

    // 역할 필터 ADMIN_OPERATOR → URL·API role 파라미터 → 1행. 목록이 불완전(역할 필터)해도 마지막 SUPER_ADMIN 판정은 서버에 맡긴다(선택지 활성).
    await page.getByTestId('filter-role').click()
    await page.getByRole('option', { name: '운영 관리자' }).click()
    await expect(page).toHaveURL(/role=ADMIN_OPERATOR/)
    await expect(page.getByTestId('row-name')).toHaveCount(1)
    expect(captured.listQueries.at(-1)?.get('role')).toBe('ADMIN_OPERATOR')

    // 등록 다이얼로그: 검색 → 결과 1건 선택 → 확인 문구·버튼 활성(POST는 호출하지 않음)
    await expect(page.getByTestId('go-provision')).toBeEnabled()
    await page.getByTestId('go-provision').click()
    const provisionDialog = page.getByTestId('admin-operator-provision-dialog')
    await expect(provisionDialog).toBeVisible()
    await expect(provisionDialog.getByTestId('provision-dialog-ok')).toBeDisabled()
    await provisionDialog.getByTestId('provision-keyword').locator('input').fill('E2E회원')
    await provisionDialog.getByTestId('provision-search').click()
    await expect(provisionDialog.getByTestId('provision-result')).toHaveCount(1)
    expect(captured.memberQueries.at(-1)?.get('keyword')).toBe('E2E회원')
    expect(captured.memberQueries.at(-1)?.get('status')).toBe('ACTIVE')
    await provisionDialog.getByTestId('provision-result').first().click()
    await expect(provisionDialog.getByTestId('provision-confirm-text')).toContainText('E2E회원 회원에게 운영 관리자 역할을 부여합니다')
    await expect(provisionDialog.getByTestId('provision-dialog-ok')).toBeEnabled()
    await provisionDialog.getByTestId('provision-dialog-close').click()
    await expect(provisionDialog).toBeHidden()

    expect(captured.writes).toEqual([])
  })
})
