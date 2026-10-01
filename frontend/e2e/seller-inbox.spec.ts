import { test, expect, type Page } from './fixtures'
import { loginAs } from './helpers/login'
import { emitInboxSignal, openStreamUrls } from './helpers/fake-event-source'
import { mockSellerMe, pickOption } from './helpers/seller-mock'

/**
 * 셀러 운영 인박스(D-248 · FE-101) E2E. 로그인은 loginAs(SELLER · 실 BE), 셀러 me·인박스·품목 단건·발송·보류는 page.route mock(상태를 가진 목 —
 * 처리·보류하면 다음 조회에서 빠진다). 원래 화면 이동만 실 화면으로 간다.
 */
const READY_ID = 'oit_E2EINBOX000000000000000001'
const READY = { type: 'DELIVERY_READY', ref: READY_ID, title: 'E2E 반찬통', subtitle: '20260930-E2E1', baseAt: '2026-09-29T09:00:00+09:00', dueAt: '2026-10-01T09:00:00+09:00', overdue: true, targetKey: 'ORDER_ITEM' }
const QUESTION = { type: 'QUESTION_UNANSWERED', ref: 'pqn_E2EQ', title: 'E2E 사이즈 문의', subtitle: 'E2E 반찬통', baseAt: '2026-09-30T09:00:00+09:00', dueAt: '2026-10-02T09:00:00+09:00', overdue: false, targetKey: 'PRODUCT_QUESTION' }
const LOW_STOCK = { type: 'LOW_STOCK', ref: 'var_E2EL', title: 'E2E 주전자', subtitle: 'E2E-SKU', overdue: false, targetKey: 'INVENTORY' }

type InboxRow = { type: string; ref: string; title: string; subtitle: string; baseAt?: string; dueAt?: string; overdue: boolean; targetKey: string }

const DETAIL = {
  orderItemId: READY_ID, orderNo: '20260930-E2E1', orderedAt: '2026-09-29T08:59:00+09:00', paidAt: '2026-09-29T09:00:00+09:00', productName: 'E2E 반찬통',
  quantity: 1, unitPrice: 32000, totalPrice: 32000, itemStatus: 'PAID',
}

// 재고 임박 패널(D-251)이 상품명으로 읽는 재고 목록 — ref(var_E2EL)와 같은 행 + 다른 옵션 1행.
const INVENTORY_ROWS = [
  { variantPublicId: 'var_E2EX', productPublicId: 'prd_E2EL', productName: 'E2E 주전자', optionLabel: '색상: 화이트', quantityOnHand: 40, quantityReserved: 0, quantityAvailable: 40 },
  { variantPublicId: 'var_E2EL', productPublicId: 'prd_E2EL', productName: 'E2E 주전자', optionLabel: '색상: 블랙', sellerSku: 'E2E-SKU', quantityOnHand: 4, quantityReserved: 1, quantityAvailable: 3 },
]

// 답안 초안(D-253) — 같은 상품의 이전 답변 근거 1건.
const QUESTION_DRAFT = '안녕하세요, 고객님. 상품에 관심 가져 주셔서 감사합니다.\n\n이전 답변과 안내 내용을 바탕으로 말씀드립니다.\n- 정사이즈로 나왔습니다.'

interface InboxMock {
  rows: InboxRow[]; shipments: unknown[]; snoozes: unknown[]; inventoryQueries: URLSearchParams[]; inbounds: { url: string; body: unknown }[]
  answers: { url: string; body: unknown }[]
}

async function mockInbox(page: Page): Promise<InboxMock> {
  const mock: InboxMock = { rows: [READY, QUESTION, LOW_STOCK], shipments: [], snoozes: [], inventoryQueries: [], inbounds: [], answers: [] }
  const remove = (ref: string): void => { mock.rows = mock.rows.filter((row) => row.ref !== ref) }
  await mockSellerMe(page)
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/inbox'), (route) => {
    const query = new URL(route.request().url()).searchParams
    const rows = query.get('tab') === 'UPCOMING' ? [] : mock.rows
    const type = query.get('type')
    const counts = new Map<string, number>()
    for (const row of rows) counts.set(row.type, (counts.get(row.type) ?? 0) + 1)
    return route.fulfill({
      json: {
        items: type ? rows.filter((row) => row.type === type) : rows,
        counts: [...counts.entries()].map(([key, count]) => ({ type: key, count })),
        truncated: false,
      },
    })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/inbox/snoozes'), (route) => {
    const snooze = route.request().postDataJSON() as { ref: string }
    mock.snoozes.push(snooze)
    remove(snooze.ref)
    return route.fulfill({ status: 204, body: '' })
  })
  await page.route((url) => url.pathname.endsWith(`/api/v1/seller/order-items/${READY_ID}/prepare-shipment`), (route) => {
    mock.shipments.push(route.request().postDataJSON())
    remove(READY_ID)
    return route.fulfill({ json: { deliveryPublicId: 'dlv_E2E_NEW', status: 'SHIPPING', carrier: 'CJ', trackingNo: 'E2E-INBOX-0001' } })
  })
  await page.route((url) => url.pathname.endsWith(`/api/v1/seller/order-items/${READY_ID}`), (route) => route.fulfill({ json: DETAIL }))
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/inventories'), (route) => {
    mock.inventoryQueries.push(new URL(route.request().url()).searchParams)
    return route.fulfill({ json: { items: INVENTORY_ROWS, page: 0, size: 100, totalCount: INVENTORY_ROWS.length, hasNext: false } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/inventories/var_E2EL/mark-inbound'), (route) => {
    mock.inbounds.push({ url: route.request().url(), body: route.request().postDataJSON() })
    remove(LOW_STOCK.ref)
    return route.fulfill({ json: { variantPublicId: 'var_E2EL', quantityOnHand: 14, quantityReserved: 1, quantityAvailable: 13 } })
  })
  await page.route((url) => url.pathname.endsWith(`/api/v1/seller/product-questions/${QUESTION.ref}/answer-draft`), (route) => route.fulfill({
    json: { draft: QUESTION_DRAFT, evidence: [{ kind: 'ANSWERED_QUESTION', title: '사이즈가 어떤가요?', summary: '정사이즈로 나왔습니다.' }], faqCandidate: false },
  }))
  await page.route((url) => url.pathname.endsWith(`/api/v1/seller/product-questions/${QUESTION.ref}/answer`), (route) => {
    mock.answers.push({ url: route.request().url(), body: route.request().postDataJSON() })
    remove(QUESTION.ref)
    return route.fulfill({ status: 204, body: '' })
  })
  return mock
}

test.describe('셀러 인박스(FE-101)', () => {
  test('① 목록 → 발송 대기 패널 처리(송장 등록) → 재조회 → 다음 항목 자동 선택 · 메뉴 배지', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/inbox')

    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 반찬통')
    await expect(page.getByTestId('seller-menu-badge-inbox')).toContainText('3')
    await expect(page.getByTestId('inbox-order-item')).toContainText('E2E 반찬통 · 1개')

    await page.getByTestId('inbox-detail-action').click()
    const dialog = page.getByTestId('seller-shipment-dialog')
    await expect(dialog).toBeVisible()
    await pickOption(page, 'shipment-carrier', 'CJ대한통운')
    await dialog.getByTestId('shipment-tracking-no').locator('input').fill('E2E-INBOX-0001')
    await dialog.getByTestId('shipment-dialog-ok').click()

    await expect(page.getByTestId('seller-toaster')).toContainText('발송 처리했습니다')
    expect(mock.shipments).toEqual([{ carrier: 'CJ', trackingNo: 'E2E-INBOX-0001' }])
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 사이즈 문의')
  })

  test('② 탭 전환(예정 빈 상태) · 재고 임박 기한 없음 · 보류 → 목록에서 빠짐', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)

    await page.getByTestId('inbox-tab-UPCOMING').click()
    await expect(page.getByTestId('inbox-empty')).toHaveText('예정된 처리 대기가 없습니다.')
    await page.getByTestId('inbox-tab-TODAY').click()

    await page.locator('[data-testid="inbox-item"][data-key="LOW_STOCK:var_E2EL"]').click()
    await expect(page.getByTestId('inbox-detail-deadline')).toHaveText('기한 없음')
    await expect(page.getByTestId('inbox-detail-action')).toHaveCount(0) // 재고 임박은 공통 처리 버튼 대신 재고 패널 입고(D-251 · ⑦)

    await page.getByTestId('inbox-detail-snooze').click()
    const dialog = page.getByTestId('inbox-snooze-dialog')
    await dialog.getByTestId('inbox-snooze-reason-input').locator('input').fill('입고 예정 확인')
    await dialog.getByTestId('inbox-snooze-preset-IN_ONE_HOUR').click()
    await dialog.getByTestId('inbox-snooze-submit').click()

    await expect(page.getByTestId('seller-toaster')).toContainText('보류했습니다')
    expect(mock.snoozes[0]).toMatchObject({ type: 'LOW_STOCK', ref: 'var_E2EL', reason: '입고 예정 확인' })
    await expect(page.locator('[data-testid="inbox-item"][data-key="LOW_STOCK:var_E2EL"]')).toHaveCount(0)
  })

  test('③ 원래 화면에서 열기: 재고 임박 → 재고 화면(상품명 검색)', async ({ page }) => {
    await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/inbox?type=LOW_STOCK')
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 주전자')
    await page.getByTestId('inbox-detail-open-origin').click()
    await page.waitForURL(/\/seller\/products\/inventory\?/)
    expect(new URL(page.url()).searchParams.get('keyword')).toBe('E2E 주전자')
  })

  test('④ 모바일: 항목 선택 시 오른쪽 드로어 · Q&A 답변 다이얼로그 열림', async ({ page }) => {
    await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/seller/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('inbox-detail-title')).toHaveCount(0)

    await page.locator('[data-testid="inbox-item"][data-key="QUESTION_UNANSWERED:pqn_E2EQ"]').click()
    const drawer = page.getByTestId('inbox-detail-drawer')
    await expect(drawer.getByTestId('inbox-detail-title')).toHaveText('E2E 사이즈 문의')
    await drawer.getByTestId('inbox-detail-action').click()
    await expect(page.getByTestId('seller-question-answer-dialog')).toContainText('E2E 사이즈 문의')
  })

  test('⑤ 모바일 드로어 닫기(FE-102): "← 목록" 버튼 · 뒤로가기 — 닫히고 인박스 목록 유지', async ({ page }) => {
    await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/seller/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    const drawer = page.getByTestId('inbox-detail-drawer')
    const item = page.locator('[data-testid="inbox-item"][data-key="LOW_STOCK:var_E2EL"]')

    // ① "← 목록" 버튼으로 닫힘 → selected 제거 · 목록 그대로 · 머리줄 유형명·손잡이
    await item.click()
    await expect(drawer).toHaveClass(/v-navigation-drawer--active/)
    await expect(drawer.getByTestId('inbox-drawer-header')).toContainText('재고 임박')
    await expect(drawer.getByTestId('inbox-drawer-handle')).toHaveCount(1)
    await page.getByRole('button', { name: '목록으로 돌아가기' }).click()
    await expect(drawer).not.toHaveClass(/v-navigation-drawer--active/)
    await page.waitForURL((url) => url.pathname === '/seller/inbox' && !url.searchParams.has('selected'))
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)

    // ② 다시 열고 브라우저 뒤로가기 → 드로어만 닫히고 인박스에 머문다
    await item.click()
    await expect(drawer).toHaveClass(/v-navigation-drawer--active/)
    await page.goBack()
    await expect(drawer).not.toHaveClass(/v-navigation-drawer--active/)
    await page.waitForURL((url) => url.pathname === '/seller/inbox' && !url.searchParams.has('selected'))
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
  })

  test('⑥ 변경 신호(FE-103): 탭당 연결 1개(사이드바·인박스 공유) · 신호 → 인박스 목록과 메뉴 배지 재조회', async ({ page }) => {
    const mock = await mockInbox(page)
    let listRequests = 0
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.endsWith('/api/v1/seller/inbox')) listRequests++
    })
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/inbox')
    await expect(page.getByTestId('inbox-item')).toHaveCount(3)
    await expect(page.getByTestId('seller-menu-badge-inbox')).toContainText('3')
    await expect.poll(() => openStreamUrls(page)).toEqual(['/api/v1/seller/inbox/stream'])
    await page.waitForLoadState('networkidle') // 마운트 때 배지·목록 첫 조회가 끝난 뒤를 기준으로 센다

    const before = listRequests
    mock.rows = mock.rows.filter((row) => row.ref !== QUESTION.ref)
    await emitInboxSignal(page)

    // 인박스 목록 1 + 메뉴 배지 1
    await expect.poll(() => listRequests).toBe(before + 2)
    await expect(page.getByTestId('inbox-item')).toHaveCount(2)
    await expect(page.getByTestId('seller-menu-badge-inbox')).toContainText('2')
  })

  test('⑦ 재고 임박 패널(D-251): 재고 목록(상품명 검색)에서 ref 행 선택 → 수치 표시 → 입고 다이얼로그 → POST 본문 → 재조회로 빠짐', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/inbox?type=LOW_STOCK')
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 주전자')

    // 같은 상품의 다른 옵션(가용 40)이 아니라 ref 옵션(가용 3)을 고른다
    await expect(page.getByTestId('inbox-stock-available')).toHaveText('3')
    expect(mock.inventoryQueries.at(-1)?.get('keyword')).toBe('E2E 주전자')
    expect(mock.inventoryQueries.at(-1)?.get('size')).toBe('100')

    await page.getByTestId('inbox-stock-inbound').click()
    const dialog = page.getByTestId('seller-inventory-adjust-dialog')
    await expect(dialog.getByTestId('adjust-title')).toHaveText('입고 처리')
    await expect(dialog.getByTestId('adjust-item')).toContainText('E2E 주전자 (색상: 블랙)')
    await dialog.getByTestId('adjust-quantity').locator('input').fill('10')
    await dialog.getByTestId('adjust-reason').locator('input').fill('추가 입고')
    await dialog.getByTestId('adjust-dialog-ok').click()

    await expect(page.getByTestId('seller-toaster')).toContainText('입고 10개 처리했습니다. 보유 14 · 가용 13')
    expect(mock.inbounds).toHaveLength(1)
    expect(mock.inbounds[0]!.body).toEqual({ quantity: 10, reason: '추가 입고' })
    await expect(page.locator('[data-testid="inbox-item"][data-key="LOW_STOCK:var_E2EL"]')).toHaveCount(0)
  })

  test('⑧ Q&A 답안 초안(D-253): 근거 표시 → 초안 사용 → 답변 제출 → 재조회로 빠짐', async ({ page }) => {
    const mock = await mockInbox(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/inbox')
    await page.locator(`[data-testid="inbox-item"][data-key="QUESTION_UNANSWERED:${QUESTION.ref}"]`).click()
    await expect(page.getByTestId('inbox-detail-title')).toHaveText('E2E 사이즈 문의')

    await page.getByTestId('inbox-detail-action').click()
    const dialog = page.getByTestId('seller-question-answer-dialog')
    await expect(dialog.getByTestId('answer-draft-evidence')).toContainText('[이전 답변] 사이즈가 어떤가요?')
    const answer = dialog.getByTestId('answer-content').locator('textarea').first()
    await expect(answer).toHaveValue('')
    await dialog.getByTestId('answer-draft-use').click()
    await expect(answer).toHaveValue(QUESTION_DRAFT)
    await dialog.getByTestId('answer-dialog-ok').click()

    await expect(page.getByTestId('seller-toaster')).toContainText('답변을 등록했습니다.')
    expect(mock.answers).toHaveLength(1)
    expect(mock.answers[0]!.body).toEqual({ content: QUESTION_DRAFT })
    await expect(page.locator(`[data-testid="inbox-item"][data-key="QUESTION_UNANSWERED:${QUESTION.ref}"]`)).toHaveCount(0)
  })
})
