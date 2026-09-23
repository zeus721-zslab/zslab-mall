import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 불일치 목록(Track 104-2 FE-66·BE D-216) E2E 스모크. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 불일치 API는
 * page.route로 mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(기본 확인 필요 조회 → 유형 필터 URL·API → 해결 다이얼로그 메모 필수·
 * POST body → 재조회 · 이미 해결 422 → warning·재조회).
 */
const ISSUES = [
  { issueId: 11, issueType: 'PG_PAYMENT_SUCCESS_CONFLICT', status: 'OPEN', orderId: 'ord_E2E1', orderNo: 'ORD-20260916-0001', pgTid: 'MOCK-TID-9',
    detail: { reason: 'ORDER_NOT_PENDING_PAYMENT', callbackType: 'SUCCESS', paymentStatus: 'PENDING', orderStatus: 'PAYMENT_EXPIRED' }, detectedAt: '2026-09-20T10:00:00' },
  { issueId: 12, issueType: 'PG_UNMATCHED_CALLBACK', status: 'OPEN', pgRefundId: 'mock_rfn_x',
    detail: { reason: 'NO_MATCHING_REFUND', callbackStatus: 'SUCCESS' }, detectedAt: '2026-09-19T10:00:00' },
]

interface Captured { listQueries: URLSearchParams[]; posts: { url: string; body: string }[] }

async function mockAdminApi(page: Page, options: { resolveStatus?: number } = {}): Promise<Captured> {
  const captured: Captured = { listQueries: [], posts: [] }
  await page.route((url) => /\/api\/v1\/admin\/reconciliation-issues\/\d+\/resolve$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    if (options.resolveStatus === 422) {
      return route.fulfill({ status: 422, contentType: 'application/problem+json', json: { code: 'RECONCILIATION_ISSUE_INVALID_STATE', detail: '이미 해결된 불일치입니다: id=11' } })
    }
    return route.fulfill({ json: { ...ISSUES[0], status: 'RESOLVED', resolvedAt: '2026-09-21T10:00:00', resolvedByName: '운영자', resolutionMemo: 'PG 확인' } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/reconciliation-issues'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const type = query.get('type')
    const filtered = type ? ISSUES.filter((issue) => issue.issueType === type) : ISSUES
    return route.fulfill({ json: { items: filtered, page: 0, size: 20, totalCount: filtered.length, hasNext: false } })
  })
  return captured
}

test.describe('관리자 불일치(FE-66)', () => {
  test('① 진입(확인 필요 기본·2행·요약·주문 없음) → 유형 필터(URL·API) → 해결 다이얼로그(FE-64 문구·메모 필수) → POST body·재조회', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/reconciliation')

    await expect(page.getByTestId('reconciliation-issue')).toHaveCount(2)
    expect(captured.listQueries[0]?.get('status')).toBe('OPEN')
    await expect(page.getByTestId('reconciliation-summary').first()).toHaveText('결제대기가 아닌 주문(만료·취소)에 결제 성공 통지가 왔습니다')
    await expect(page.getByTestId('reconciliation-facts').first()).toContainText('주문 미결제 종료')
    await expect(page.getByTestId('reconciliation-open-order')).toHaveCount(1) // 주문 없는 통지는 링크 없음
    await expect(page.getByTestId('reconciliation-issue').nth(1)).toContainText('주문 없음')

    await page.getByTestId('reconciliation-filter-type').click()
    await page.getByRole('option', { name: '대상 없는 PG 통지', exact: true }).click()
    await expect(page).toHaveURL(/type=PG_UNMATCHED_CALLBACK/)
    await expect(page.getByTestId('reconciliation-issue')).toHaveCount(1)
    expect(captured.listQueries.at(-1)?.get('type')).toBe('PG_UNMATCHED_CALLBACK')

    await page.goto('/admin/orders/reconciliation')
    await page.getByTestId('reconciliation-resolve').first().click()
    const dialog = page.getByTestId('admin-reconciliation-resolve-dialog')
    await expect(dialog.getByTestId('resolve-message')).toContainText('데이터는 바뀌지 않으니')
    await expect(dialog.getByTestId('resolve-message')).toContainText('되돌릴 수 없습니다.')
    await expect(dialog.getByTestId('resolve-dialog-ok')).toBeDisabled()
    await dialog.getByTestId('resolve-memo').locator('textarea').first().fill('  PG 확인  ')
    const listsBefore = captured.listQueries.length
    await dialog.getByTestId('resolve-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="success"]')).toContainText('불일치를 해결됨으로 표시했습니다.')
    await expect(dialog).toBeHidden()
    expect(captured.posts[0]!.url).toContain('/admin/reconciliation-issues/11/resolve')
    expect(JSON.parse(captured.posts[0]!.body)).toEqual({ memo: 'PG 확인' })
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listsBefore)
  })

  test('② 이미 해결된 건(422 RECONCILIATION_ISSUE_INVALID_STATE) → warning 토스트·다이얼로그 닫힘·재조회', async ({ page }) => {
    const captured = await mockAdminApi(page, { resolveStatus: 422 })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/reconciliation')
    await page.getByTestId('reconciliation-resolve').first().click()
    const dialog = page.getByTestId('admin-reconciliation-resolve-dialog')
    await dialog.getByTestId('resolve-memo').locator('textarea').first().fill('확인')
    const listsBefore = captured.listQueries.length
    await dialog.getByTestId('resolve-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toContainText('이미 해결된 불일치입니다.')
    await expect(dialog).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listsBefore)
  })
})
