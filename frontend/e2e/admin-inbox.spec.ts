import { test, expect, type Page } from './fixtures'
import { loginAs } from './helpers/login'
import { emitInboxSignal, openStreamUrls } from './helpers/fake-event-source'

/**
 * 관리자 운영 인박스(D-248 · FE-101) E2E. 로그인은 loginAs(ADMIN · 실 BE), 인박스 조회·보류·문의 답변은 page.route mock(상태를 가진 목 —
 * 처리·보류하면 다음 조회에서 빠진다)으로 로컬 DB를 바꾸지 않고 결정적으로 검증한다. 원래 화면 이동만 실 화면으로 간다.
 */
const INQUIRY_A = { type: 'INQUIRY_UNANSWERED', ref: 'inq_E2EA', title: 'E2E 배송이 늦어요', subtitle: 'DELIVERY', baseAt: '2026-09-29T09:00:00+09:00', dueAt: '2026-09-30T09:00:00+09:00', overdue: true, targetKey: 'INQUIRY' }
const INQUIRY_B = { type: 'INQUIRY_UNANSWERED', ref: 'inq_E2EB', title: 'E2E 교환 문의', subtitle: 'CLAIM', baseAt: '2026-09-30T09:00:00+09:00', dueAt: '2026-10-01T09:00:00+09:00', overdue: true, targetKey: 'INQUIRY' }
const FOLLOWUP = { type: 'CLAIM_FOLLOWUP', ref: 'clm_E2EF:REFUND', title: 'E2E 냄비', subtitle: 'ORD-E2E-1', baseAt: '2026-09-29T12:00:00+09:00', dueAt: '2026-10-01T12:00:00+09:00', overdue: true, targetKey: 'CLAIM' }
const PRODUCT = { type: 'PRODUCT_APPROVAL', ref: 'prd_E2EP', title: 'E2E 승인 대기 상품', subtitle: 'E2E 셀러샵', baseAt: '2099-01-01T09:00:00+09:00', dueAt: '2099-01-02T09:00:00+09:00', overdue: false, targetKey: 'PRODUCT' }

// 클레임 접수(D-250) — 승인 제안 2(취소·반품) · 검토 필요 1. 클레임 시나리오(⑧⑨)만 옵트인으로 싣는다(①~⑦ 건수 불변).
const CLAIM_CANCEL = { type: 'CLAIM_REQUESTED', ref: 'clm_E2EC', title: 'E2E 머그컵', subtitle: 'ORD-E2E-2', baseAt: '2026-09-29T10:00:00+09:00', dueAt: '2026-09-30T10:00:00+09:00', overdue: true, targetKey: 'CLAIM', claimType: 'CANCEL', suggestion: 'APPROVE' }
const CLAIM_RETURN = { type: 'CLAIM_REQUESTED', ref: 'clm_E2ER', title: 'E2E 텀블러', subtitle: 'ORD-E2E-3', baseAt: '2026-09-29T11:00:00+09:00', dueAt: '2026-09-30T11:00:00+09:00', overdue: true, targetKey: 'CLAIM', claimType: 'RETURN', suggestion: 'APPROVE' }
const CLAIM_REVIEW = { type: 'CLAIM_REQUESTED', ref: 'clm_E2EV', title: 'E2E 접시', subtitle: 'ORD-E2E-4', baseAt: '2026-09-29T12:00:00+09:00', dueAt: '2026-09-30T12:00:00+09:00', overdue: true, targetKey: 'CLAIM', claimType: 'RETURN', suggestion: 'REVIEW' }

// P1c(D-251) — 유형별 패널 시나리오(⑩~)는 today를 직접 넘긴다. 부제 없는 행은 BE non_null 직렬화대로 키를 뺀다.
const RECONCILIATION = { type: 'RECONCILIATION_OPEN', ref: '9101', title: 'ITEM_STATE_DRIFT', baseAt: '2026-09-29T08:00:00+09:00', dueAt: '2026-09-30T08:00:00+09:00', overdue: true, targetKey: 'RECONCILIATION' }
const SETTLEMENT_PAYOUT = { type: 'SETTLEMENT_PAYOUT', ref: '7201', title: 'E2E 정산상사', baseAt: '2026-09-30T00:00:00+09:00', dueAt: '2026-09-30T23:59:59.999+09:00', overdue: true, targetKey: 'SETTLEMENT' }

// P3(D-252) — 셀러 지연 행(ref = sellerPublicId · 부제 = 유형별 초과 건수).
const SELLER_DELAY_A = { type: 'SELLER_DELAY', ref: 'slr_E2EDA', title: 'E2E 지연상회', subtitle: '발송 대기 2건 · Q&A 미답변 1건', baseAt: '2026-09-28T09:00:00+09:00', dueAt: '2026-09-30T09:00:00+09:00', overdue: true, targetKey: 'SELLER' }
const SELLER_DELAY_B = { type: 'SELLER_DELAY', ref: 'slr_E2EDB', title: 'E2E 느림상점', subtitle: 'Q&A 미답변 1건', baseAt: '2026-09-28T10:00:00+09:00', dueAt: '2026-09-30T10:00:00+09:00', overdue: true, targetKey: 'SELLER' }

type InboxRow = Omit<typeof INQUIRY_A, 'subtitle'> & { subtitle?: string; claimType?: string; suggestion?: string }

interface InboxMock {
  today: InboxRow[]
  upcoming: InboxRow[]
  queries: URLSearchParams[]
  snoozes: unknown[]
  answers: string[]
  answerBodies: unknown[]
  approves: string[]
  bulkBodies: unknown[]
  /** 문의 답안 초안 응답(D-253 · 기본 = 근거 없음 · FAQ 후보 아님 — ②의 흐름을 바꾸지 않는다). */
  draft: { draft: string | null; evidence: { kind: string; title: string; summary: string }[]; faqCandidate: boolean }
  faqBodies: unknown[]
  /** 처리된 항목을 다음 조회에서 뺀다(유형별 처리 목은 각 시나리오가 단다). */
  remove: (ref: string) => void
}

/** 단건 조회 응답(BE AdminClaimDetailResponse) — 행 정보로 만든다. */
function claimDetail(row: InboxRow) {
  const cancel = row.claimType === 'CANCEL'
  return {
    claim: {
      claimId: row.ref, type: row.claimType, status: 'REQUESTED', requestedAt: row.baseAt, orderNo: row.subtitle, buyerName: 'E2E 구매자',
      productName: row.title, quantity: 1, amount: 15000, itemRemainingRefundable: 15000, reasonCode: cancel ? 'ORDER_MISTAKE' : 'PRODUCT_DEFECT',
      pgRefundSucceeded: false, availableActions: ['APPROVE', 'REJECT'], attachmentCount: cancel ? 0 : 1,
    },
    suggestion: cancel
      ? { suggestion: 'APPROVE', ruleKey: 'UNSHIPPED_CANCEL', reason: '미출고 취소 요청' }
      : { suggestion: row.suggestion, ruleKey: row.suggestion === 'APPROVE' ? 'DEFECT_WITH_EVIDENCE' : 'DEFECT_WITHOUT_EVIDENCE', reason: row.suggestion === 'APPROVE' ? '증빙 첨부' : '증빙 없음' },
  }
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

async function mockInbox(page: Page, options: { claims?: boolean; today?: InboxRow[] } = {}): Promise<InboxMock> {
  const today: InboxRow[] = options.today ?? (options.claims ? [CLAIM_CANCEL, CLAIM_RETURN, CLAIM_REVIEW, INQUIRY_A] : [INQUIRY_A, INQUIRY_B, FOLLOWUP])
  const remove = (ref: string): void => {
    mock.today = mock.today.filter((row) => row.ref !== ref)
    mock.upcoming = mock.upcoming.filter((row) => row.ref !== ref)
  }
  const mock: InboxMock = {
    today, upcoming: [PRODUCT], queries: [], snoozes: [], answers: [], answerBodies: [], approves: [], bulkBodies: [],
    draft: { draft: null, evidence: [], faqCandidate: false }, faqBodies: [], remove,
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
    mock.answerBodies.push(route.request().postDataJSON())
    remove(inquiryId)
    return route.fulfill({ status: 204, body: '' })
  })
  await page.route((url) => /\/api\/v1\/admin\/inquiries\/inq_[^/]+\/answer-draft$/.test(url.pathname), (route) => route.fulfill({ json: mock.draft }))
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/faqs'), (route) => {
    mock.faqBodies.push(route.request().postDataJSON())
    return route.fulfill({ status: 201, json: { id: 9901 } })
  })
  // 클레임 단건·승인·일괄 승인(D-250). 일괄 승인은 첫 항목만 성공(제거)하고 나머지는 제안 불일치로 실패시킨다.
  await page.route((url) => /\/api\/v1\/admin\/claims\/clm_[^/]+$/.test(url.pathname), (route) => {
    const ref = new URL(route.request().url()).pathname.split('/').at(-1) ?? ''
    const row = mock.today.find((candidate) => candidate.ref === ref)
    return row ? route.fulfill({ json: claimDetail(row) }) : route.fulfill({ status: 404, json: { code: 'CLAIM_NOT_FOUND' } })
  })
  await page.route((url) => /\/api\/v1\/admin\/claims\/clm_[^/]+\/approve$/.test(url.pathname), (route) => {
    const ref = route.request().url().match(/claims\/(clm_[^/]+)\/approve/)?.[1] ?? ''
    mock.approves.push(ref)
    remove(ref)
    return route.fulfill({ json: { publicId: ref, status: 'APPROVED' } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/claims/bulk/approve'), (route) => {
    const body = route.request().postDataJSON() as { claimPublicIds: string[] }
    mock.bulkBodies.push(body)
    const [first, ...rest] = body.claimPublicIds
    if (first) remove(first)
    return route.fulfill({
      json: {
        results: [
          { claimPublicId: first, success: true },
          ...rest.map((claimPublicId) => ({ claimPublicId, success: false, code: 'CLAIM_SUGGESTION_MISMATCH', message: '승인 제안이 아닙니다: 증빙 없음' })),
        ],
        successCount: 1,
        failureCount: rest.length,
      },
    })
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

  test('⑦ 변경 신호(FE-103): 탭당 연결 1개(사이드바·인박스 공유) · 신호 → 인박스 목록과 메뉴 배지 재조회', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('admin-menu-badge-inbox')).toContainText('3')
    await expect.poll(() => openStreamUrls(page)).toEqual(['/api/v1/admin/inbox/stream'])
    await page.waitForLoadState('networkidle') // 마운트 때 배지·목록 첫 조회가 끝난 뒤를 기준으로 센다

    const before = mock.queries.length
    // 선택되지 않은 항목을 뺀다(선택이 바뀌면 URL 변경으로 목록 조회가 따로 한 번 더 난다)
    mock.today = mock.today.filter((row) => row.ref !== INQUIRY_B.ref)
    await emitInboxSignal(page)

    // 인박스 목록 1 + 메뉴 배지 1
    await expect.poll(() => mock.queries.length).toBe(before + 2)
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)
    await expect(page.getByTestId('admin-menu-badge-inbox')).toContainText('2')
  })

  test('⑧ 클레임 접수 패널(D-250): 유형·제안 표시 → 단건 조회 근거 → 승인 확인(즉시 환불) → 재조회 · 다음 항목', async ({ page }) => {
    const mock = await mockInbox(page, { claims: true })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(4)
    const cancelRow = page.locator('[data-testid="inbox-item"][data-key="CLAIM_REQUESTED:clm_E2EC"]')
    await expect(cancelRow).toContainText('취소')
    await expect(cancelRow.getByTestId('inbox-item-suggestion')).toHaveText('승인 제안')

    // 첫 항목(취소) 자동 선택 → 패널이 단건 조회로 제안 근거를 보인다
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 머그컵')
    await expect(page.getByTestId('inbox-claim-suggestion-reason')).toHaveText('미출고 취소 요청')
    await expect(page.getByTestId('inbox-claim-reason')).toHaveText('주문 실수')

    await page.getByTestId('inbox-claim-approve').click()
    const dialog = page.getByTestId('inbox-claim-approve-dialog')
    await expect(dialog).toContainText('승인 즉시 환불')
    await dialog.getByTestId('inbox-claim-approve-dialog-ok').click()

    await expect(page.getByTestId('admin-toaster')).toContainText('취소 요청을 승인했습니다.')
    expect(mock.approves).toEqual(['clm_E2EC'])
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 텀블러')
  })

  test('⑨ 일괄 승인(D-250): 승인 제안 행만 체크 → 확인(유형별 건수) → 부분 실패 토스트 → 결과 상세 → 재조회', async ({ page }) => {
    const mock = await mockInbox(page, { claims: true })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(4)
    // 체크 상자는 승인 제안 행(2)에만 있다 — 검토 필요·문의 행에는 없다
    await expect(page.getByTestId('inbox-bulk-check')).toHaveCount(2)

    await page.locator('[data-testid="inbox-item"][data-key="CLAIM_REQUESTED:clm_E2EC"]').getByTestId('inbox-bulk-check').click()
    await page.locator('[data-testid="inbox-item"][data-key="CLAIM_REQUESTED:clm_E2ER"]').getByTestId('inbox-bulk-check').click()
    await expect(page.getByTestId('inbox-bulk-count')).toHaveText('2건 선택 (최대 20건)')
    // 체크는 상세 선택을 바꾸지 않는다
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 머그컵')

    await page.getByTestId('inbox-bulk-approve').click()
    const dialog = page.getByTestId('inbox-bulk-confirm-dialog')
    await expect(dialog).toContainText('선택한 클레임 2건을 승인합니다.')
    await expect(dialog).toContainText('취소 1건 · 반품 1건')
    await expect(dialog).toContainText('취소는 승인 즉시 환불이 진행됩니다.')
    await dialog.getByTestId('inbox-bulk-confirm-dialog-ok').click()

    const warningToast = page.locator('[data-sonner-toast][data-type="warning"]')
    await expect(warningToast).toContainText('일괄 승인 — 성공 1 / 실패 1')
    expect(mock.bulkBodies).toEqual([{ claimPublicIds: ['clm_E2EC', 'clm_E2ER'] }])
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('inbox-bulk-bar')).toHaveCount(0)

    await warningToast.getByRole('button', { name: '상세 보기' }).click()
    const result = page.getByTestId('admin-claim-bulk-result-dialog')
    await expect(result.getByTestId('claim-bulk-result-failure-item')).toContainText('E2E 텀블러')
    await expect(result.getByTestId('claim-bulk-result-failure-item')).toContainText('지금은 승인 제안이 아니어서 승인하지 않았습니다.')
  })

  test('⑩ 정합성 불일치 패널(D-251): 단건 조회 사유·주문 → 해결 다이얼로그(메모 필수) → POST 본문 → 재조회로 빠짐', async ({ page }) => {
    const mock = await mockInbox(page, { today: [RECONCILIATION, INQUIRY_A] })
    const resolves: { url: string; body: unknown }[] = []
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/reconciliation-issues/9101'), (route) => route.fulfill({
      json: { issueId: 9101, issueType: 'ITEM_STATE_DRIFT', status: 'OPEN', orderNo: 'ORD-E2E-9', detail: { itemStatus: 'PAID' }, detectedAt: '2026-09-29T08:00:00+09:00', resolvedBySystem: false },
    }))
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/reconciliation-issues/9101/resolve'), (route) => {
      resolves.push({ url: route.request().url(), body: route.request().postDataJSON() })
      mock.remove('9101')
      return route.fulfill({ json: { issueId: 9101, issueType: 'ITEM_STATE_DRIFT', status: 'RESOLVED', detail: {}, detectedAt: '2026-09-29T08:00:00+09:00', resolvedBySystem: false } })
    })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)

    await expect(page.getByTestId('inbox-reconciliation-summary')).toHaveText('품목 상태 어긋남')
    await expect(page.getByTestId('inbox-reconciliation-panel')).toContainText('ORD-E2E-9')
    await page.getByTestId('inbox-reconciliation-resolve').click()
    const dialog = page.getByTestId('admin-reconciliation-resolve-dialog')
    await expect(dialog.getByTestId('resolve-dialog-ok')).toBeDisabled()
    await dialog.getByTestId('resolve-memo').locator('textarea').first().fill('PG 확인 후 보정')
    await dialog.getByTestId('resolve-dialog-ok').click()

    await expect(page.locator('[data-sonner-toast][data-type="success"]')).toContainText('불일치를 해결됨으로 표시했습니다.')
    expect(resolves).toHaveLength(1)
    expect(resolves[0]!.body).toEqual({ memo: 'PG 확인 후 보정' })
    await expect(page.getByTestId('inbox-item')).toHaveCount(1)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 배송이 늦어요')
  })

  test('⑪ 정산 지급 패널(D-251): 단건 조회 지급액·계좌 → 지급완료 확인(금액·계좌 문구) → POST → 재조회로 빠짐', async ({ page }) => {
    const mock = await mockInbox(page, { today: [SETTLEMENT_PAYOUT, INQUIRY_A] })
    const pays: string[] = []
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/settlements/7201'), (route) => route.fulfill({
      json: {
        id: 7201, seller: { publicId: 'slr_E2E', companyName: 'E2E 정산상사' }, periodStart: '2026-08-01T00:00:00+09:00', periodEnd: '2026-08-31T23:59:59+09:00',
        grossAmount: 100000, feeAmount: 10000, refundAmount: 0, carryoverAmount: 0, netAmount: 90000, status: 'CONFIRMED', scheduledPayDate: '2026-09-30',
        bankAccountRegistered: true, saleItemCount: 3, refundItemCount: 0, carryoverItemCount: 0,
        bankAccount: { id: 1, bankCode: '004', accountHolder: 'E2E 정산상사', accountNumberSuffix: '1234', snapshot: false },
      },
    }))
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/settlements/7201/pay'), (route) => {
      pays.push(route.request().method())
      mock.remove('7201')
      return route.fulfill({ json: { settlementId: 7201, status: 'PAID', paidAt: '2026-09-30T10:00:00+09:00' } })
    })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)

    await expect(page.getByTestId('inbox-settlement-net')).toHaveText('90,000원')
    await expect(page.getByTestId('inbox-settlement-confirm')).toHaveCount(0) // 지급 대기는 지급만
    await page.getByTestId('inbox-settlement-pay').click()
    const dialog = page.getByTestId('inbox-settlement-pay-dialog')
    await expect(dialog).toContainText('90,000원')
    await expect(dialog).toContainText('1234')
    await dialog.getByTestId('inbox-settlement-pay-dialog-ok').click()

    await expect(page.locator('[data-sonner-toast][data-type="success"]')).toContainText('지급완료로 처리했습니다.')
    expect(pays).toEqual(['POST'])
    await expect(page.getByTestId('inbox-item')).toHaveCount(1)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 배송이 늦어요')
  })

  test('⑫ 셀러 지연 일괄 독촉(D-252): 패널 초과 건수 → 클레임 체크 후 셀러 체크하면 선택이 셀러로 바뀜 → 2곳 독촉 확인 → POST 본문 → 결과 토스트·상세', async ({ page }) => {
    await mockInbox(page, { today: [SELLER_DELAY_A, SELLER_DELAY_B, CLAIM_CANCEL] })
    const nudgeBodies: unknown[] = []
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/inbox/seller-delays/slr_E2EDA'), (route) => route.fulfill({
      json: { sellerPublicId: 'slr_E2EDA', companyName: 'E2E 지연상회', deliveryReadyOverdueCount: 2, questionUnansweredOverdueCount: 1 },
    }))
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/inbox/seller-delays/nudge'), (route) => {
      nudgeBodies.push(route.request().postDataJSON())
      return route.fulfill({
        json: {
          results: [{ sellerPublicId: 'slr_E2EDA', result: 'SENT' }, { sellerPublicId: 'slr_E2EDB', result: 'COOLDOWN' }],
          sentCount: 1, failedCount: 0, noRecipientCount: 0, cooldownCount: 1, noDelayCount: 0,
        },
      })
    })
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)

    // 첫 행(셀러 지연) 패널: 지금 다시 센 유형별 초과 건수 · 독촉 이력 없음
    await expect(page.getByTestId('inbox-seller-delay-delivery')).toHaveText('2건')
    await expect(page.getByTestId('inbox-seller-delay-question')).toHaveText('1건')
    await expect(page.getByTestId('inbox-seller-delay-last-nudged')).toHaveText('없음')

    // 한 번에 한 종류: 클레임을 고른 뒤 셀러를 고르면 클레임 선택이 풀린다
    await page.locator('[data-testid="inbox-item"][data-key="CLAIM_REQUESTED:clm_E2EC"]').getByTestId('inbox-bulk-check').click()
    await expect(page.getByTestId('inbox-bulk-count')).toHaveText('1건 선택 (최대 20건)')
    await page.locator('[data-testid="inbox-item"][data-key="SELLER_DELAY:slr_E2EDA"]').getByTestId('inbox-bulk-check').click()
    await expect(page.getByTestId('inbox-bulk-bar')).toHaveCount(0)
    await page.locator('[data-testid="inbox-item"][data-key="SELLER_DELAY:slr_E2EDB"]').getByTestId('inbox-bulk-check').click()
    await expect(page.getByTestId('inbox-nudge-count')).toHaveText('셀러 2곳 선택 (최대 20곳)')

    await page.getByTestId('inbox-nudge-send').click()
    const dialog = page.getByTestId('inbox-nudge-confirm-dialog')
    await expect(dialog).toContainText('선택한 셀러 2곳에 처리 지연 독촉 SMS를 보냅니다.')
    await dialog.getByTestId('inbox-nudge-confirm-dialog-ok').click()

    const warningToast = page.locator('[data-sonner-toast][data-type="warning"]')
    await expect(warningToast).toContainText('셀러 독촉 — 발송 1 / 24시간 내 독촉함 1')
    expect(nudgeBodies).toEqual([{ sellerPublicIds: ['slr_E2EDA', 'slr_E2EDB'] }])
    await expect(page.getByTestId('inbox-nudge-bar')).toHaveCount(0)

    await warningToast.getByRole('button', { name: '상세 보기' }).click()
    const items = page.getByTestId('admin-seller-nudge-result-dialog').getByTestId('seller-nudge-result-item')
    await expect(items.nth(0)).toContainText('E2E 지연상회')
    await expect(items.nth(0)).toContainText('발송')
    await expect(items.nth(1)).toContainText('E2E 느림상점')
    await expect(items.nth(1)).toContainText('24시간 내 독촉함')
  })

  test('⑬ 답안 초안·FAQ 후보(D-253): 근거 표시 → 초안 사용 → FAQ 등록 체크 → 답변 저장 → 미리 채운 FAQ 등록 → 재조회', async ({ page }) => {
    const mock = await mockInbox(page)
    const draft = '안녕하세요, 고객님. 배송 관련 문의 주셔서 감사합니다.\n\n확인한 배송 관련 내용을 안내해 드립니다.\n- 주문 상태: 배송중 · 배송 상태: 배송중'
    mock.draft = { draft, evidence: [{ kind: 'ORDER', title: '주문 ORD-E2E-9', summary: '주문 상태: 배송중 · 배송 상태: 배송중' }], faqCandidate: true }
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/inbox')
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 배송이 늦어요')

    await page.getByTestId('inbox-detail-action').click()
    const dialog = page.getByTestId('admin-inquiry-answer-dialog')
    await expect(dialog.getByTestId('answer-draft-evidence')).toContainText('[주문] 주문 ORD-E2E-9')
    const answer = dialog.getByTestId('answer-content').locator('textarea').first()
    await expect(answer).toHaveValue('')
    await dialog.getByTestId('answer-draft-use').click()
    await expect(answer).toHaveValue(draft)
    await expect(dialog.getByTestId('answer-faq-candidate')).toContainText('FAQ 후보')
    await dialog.getByTestId('answer-faq-register').locator('input').check()
    await dialog.getByTestId('answer-dialog-ok').click()

    await expect(page.getByTestId('admin-toaster')).toContainText('답변을 등록했습니다.')
    expect(mock.answerBodies).toEqual([{ content: draft }])
    const faqDialog = page.getByTestId('admin-faq-dialog')
    await expect(faqDialog.getByTestId('faq-question').locator('input')).toHaveValue('E2E 배송이 늦어요')
    await expect(faqDialog.getByTestId('faq-answer').locator('textarea').first()).toHaveValue(draft)
    await faqDialog.getByTestId('faq-dialog-ok').click()

    await expect(page.getByTestId('admin-toaster')).toContainText('FAQ를 등록했습니다.')
    expect(mock.faqBodies).toEqual([{ category: 'DELIVERY', question: 'E2E 배송이 늦어요', answer: draft, visible: true }])
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 교환 문의')
  })
})
