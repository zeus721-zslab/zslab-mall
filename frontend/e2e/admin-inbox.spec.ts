import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 운영 인박스(D-248 · FE-101) E2E. 로그인은 loginAs(ADMIN · 실 BE), 인박스 조회·보류·문의 답변은 page.route mock(상태를 가진 목 —
 * 처리·보류하면 다음 조회에서 빠진다)으로 로컬 DB를 바꾸지 않고 결정적으로 검증한다. 원래 화면 이동만 실 화면으로 간다.
 */
const INQUIRY_A = { type: 'INQUIRY_UNANSWERED', ref: 'inq_E2EA', title: 'E2E 배송이 늦어요', subtitle: 'DELIVERY', baseAt: '2026-09-29T09:00:00+09:00', dueAt: '2026-09-30T09:00:00+09:00', overdue: true, targetKey: 'INQUIRY' }
const INQUIRY_B = { type: 'INQUIRY_UNANSWERED', ref: 'inq_E2EB', title: 'E2E 교환 문의', subtitle: 'CLAIM', baseAt: '2026-09-30T09:00:00+09:00', dueAt: '2026-10-01T09:00:00+09:00', overdue: true, targetKey: 'INQUIRY' }
const FOLLOWUP = { type: 'CLAIM_FOLLOWUP', ref: 'clm_E2EF:REFUND', title: 'E2E 냄비', subtitle: 'ORD-E2E-1', baseAt: '2026-09-29T12:00:00+09:00', dueAt: '2026-10-01T12:00:00+09:00', overdue: true, targetKey: 'CLAIM' }
const PRODUCT = { type: 'PRODUCT_APPROVAL', ref: 'prd_E2EP', title: 'E2E 승인 대기 상품', subtitle: 'E2E 셀러샵', baseAt: '2099-01-01T09:00:00+09:00', dueAt: '2099-01-02T09:00:00+09:00', overdue: false, targetKey: 'PRODUCT' }

type InboxRow = typeof INQUIRY_A

interface InboxMock {
  today: InboxRow[]
  upcoming: InboxRow[]
  queries: URLSearchParams[]
  snoozes: unknown[]
  answers: string[]
}

function body(rows: InboxRow[], type: string | null) {
  const counts = new Map<string, number>()
  for (const row of rows) counts.set(row.type, (counts.get(row.type) ?? 0) + 1)
  return {
    items: type ? rows.filter((row) => row.type === type) : rows,
    counts: [...counts.entries()].map(([key, count]) => ({ type: key, count })),
    truncated: false,
  }
}

async function mockInbox(page: Page): Promise<InboxMock> {
  const mock: InboxMock = { today: [INQUIRY_A, INQUIRY_B, FOLLOWUP], upcoming: [PRODUCT], queries: [], snoozes: [], answers: [] }
  const remove = (ref: string): void => {
    mock.today = mock.today.filter((row) => row.ref !== ref)
    mock.upcoming = mock.upcoming.filter((row) => row.ref !== ref)
  }
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/inbox'), (route) => {
    const query = new URL(route.request().url()).searchParams
    mock.queries.push(query)
    const rows = query.get('tab') === 'UPCOMING' ? mock.upcoming : mock.today
    return route.fulfill({ json: body(rows, query.get('type')) })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/inbox/snoozes'), (route) => {
    const snooze = route.request().postDataJSON() as { ref: string }
    mock.snoozes.push(snooze)
    remove(snooze.ref)
    return route.fulfill({ status: 204, body: '' })
  })
  await page.route((url) => /\/api\/v1\/admin\/inquiries\/inq_[^/]+\/answer$/.test(url.pathname), (route) => {
    const inquiryId = route.request().url().match(/inquiries\/(inq_[^/]+)\/answer/)?.[1] ?? ''
    mock.answers.push(inquiryId)
    remove(inquiryId)
    return route.fulfill({ status: 204, body: '' })
  })
  return mock
}

test.describe('관리자 인박스(FE-101)', () => {
  test('① 목록(오늘 3 · 첫 항목 자동 선택) → 예정 탭 → 유형 칩 필터 → 메뉴 배지', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')

    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 배송이 늦어요')
    await expect(page).toHaveURL(/selected=INQUIRY_UNANSWERED%3Ainq_E2EA|selected=INQUIRY_UNANSWERED:inq_E2EA/)
    await expect(page.getByTestId('inbox-type-chip-INQUIRY_UNANSWERED')).toContainText('2')
    await expect(page.getByTestId('admin-menu-badge-inbox')).toContainText('3')

    await page.getByTestId('inbox-tab-UPCOMING').click()
    await expect(page).toHaveURL(/tab=UPCOMING/)
    await expect(page.getByTestId('inbox-item')).toHaveCount(1)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 승인 대기 상품')
    await expect.poll(() => mock.queries.at(-1)?.get('tab')).toBe('UPCOMING')

    await page.getByTestId('inbox-tab-TODAY').click()
    await page.getByTestId('inbox-type-chip-CLAIM_FOLLOWUP').click()
    await expect(page).toHaveURL(/type=CLAIM_FOLLOWUP/)
    await expect(page.getByTestId('inbox-item')).toHaveCount(1)
    await expect.poll(() => mock.queries.at(-1)?.get('type')).toBe('CLAIM_FOLLOWUP')
  })

  test('② 패널 처리: 1:1 문의 답변 → 재조회 → 다음 항목 자동 선택', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 배송이 늦어요')

    await page.getByTestId('inbox-detail-action').click()
    const dialog = page.getByTestId('admin-inquiry-answer-dialog')
    await expect(dialog).toContainText('E2E 배송이 늦어요')
    await dialog.getByTestId('answer-content').locator('textarea').first().fill('곧 출고됩니다.')
    await dialog.getByTestId('answer-dialog-ok').click()

    await expect(page.getByTestId('admin-toaster')).toContainText('답변을 등록했습니다.')
    expect(mock.answers).toEqual(['inq_E2EA'])
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 교환 문의')
  })

  test('③ 보류(사유 칩 · 프리셋) → 목록에서 빠짐 · PUT 본문', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await page.locator('[data-testid="inbox-item"][data-key="INQUIRY_UNANSWERED:inq_E2EB"]').click()
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 교환 문의')

    await page.getByTestId('inbox-detail-snooze').click()
    const dialog = page.getByTestId('inbox-snooze-dialog')
    await expect(dialog.getByTestId('inbox-snooze-submit')).toBeDisabled()
    await dialog.getByTestId('inbox-snooze-reason-1').click()
    await dialog.getByTestId('inbox-snooze-preset-TOMORROW_MORNING').click()
    await dialog.getByTestId('inbox-snooze-submit').click()

    await expect(page.getByTestId('admin-toaster')).toContainText('보류했습니다')
    expect(mock.snoozes).toHaveLength(1)
    expect(mock.snoozes[0]).toMatchObject({ type: 'INQUIRY_UNANSWERED', ref: 'inq_E2EB', reason: '고객 회신 대기' })
    expect((mock.snoozes[0] as { untilAt: string }).untilAt).toMatch(/T09:00:00\+09:00$/)
    await expect(page.locator('[data-testid="inbox-item"][data-key="INQUIRY_UNANSWERED:inq_E2EB"]')).toHaveCount(0)
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)
  })

  test('④ 원래 화면에서 열기: 클레임 후속(환불 단계) → 클레임 목록 action·주문번호 필터', async ({ page }) => {
    await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox?type=CLAIM_FOLLOWUP')
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 냄비')
    await expect(page.getByTestId('inbox-detail-action')).toHaveCount(0) // 클레임 후속은 패널 처리 없음

    await page.getByTestId('inbox-detail-open-origin').click()
    await page.waitForURL(/\/admin\/orders\/claims\?/)
    const url = new URL(page.url())
    expect(url.searchParams.get('action')).toBe('INITIATE_REFUND')
    expect(url.searchParams.get('keyword')).toBe('ORD-E2E-1')
  })

  test('⑤ 모바일: 목록 전체 폭(자동 선택 없음) → 항목 선택 시 오른쪽 드로어 상세', async ({ page }) => {
    await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('inbox-detail-title')).toHaveCount(0)

    await page.locator('[data-testid="inbox-item"][data-key="CLAIM_FOLLOWUP:clm_E2EF:REFUND"]').click()
    const drawer = page.getByTestId('inbox-detail-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByTestId('inbox-detail-title')).toHaveText('E2E 냄비')
    // 선택은 URL query라 라우트가 바뀌어도 드로어가 스스로 닫히지 않는다(disable-route-watcher)
    await expect(page).toHaveURL(/selected=/)
    await expect(drawer.getByTestId('inbox-detail-open-origin')).toBeVisible()
    // FE-102: 머리줄 가운데 유형명 · 스와이프 단서 손잡이
    await expect(drawer.getByTestId('inbox-drawer-header')).toContainText('클레임 후속')
    await expect(drawer.getByTestId('inbox-drawer-handle')).toHaveCount(1)
  })

  test('⑥ 모바일 드로어 닫기(FE-102): "← 목록" 버튼 · 뒤로가기 — 닫히고 인박스 목록 유지', async ({ page }) => {
    await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    const drawer = page.getByTestId('inbox-detail-drawer')
    const item = page.locator('[data-testid="inbox-item"][data-key="INQUIRY_UNANSWERED:inq_E2EB"]')

    // ① "← 목록" 버튼으로 닫힘 → selected 제거 · 목록 그대로
    await item.click()
    await expect(drawer).toHaveClass(/v-navigation-drawer--active/)
    await expect(page.getByRole('button', { name: '목록으로 돌아가기' })).toBeVisible()
    await page.getByTestId('inbox-drawer-back').click()
    await expect(drawer).not.toHaveClass(/v-navigation-drawer--active/)
    await page.waitForURL((url) => url.pathname === '/admin/inbox' && !url.searchParams.has('selected'))
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)

    // ② 다시 열고 브라우저 뒤로가기 → 드로어만 닫히고 인박스에 머문다(목록을 열 때 쌓은 기록 1건만 소비)
    await item.click()
    await expect(drawer).toHaveClass(/v-navigation-drawer--active/)
    await page.goBack()
    await expect(drawer).not.toHaveClass(/v-navigation-drawer--active/)
    await page.waitForURL((url) => url.pathname === '/admin/inbox' && !url.searchParams.has('selected'))
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
  })
})
