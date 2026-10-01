import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
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

interface InboxMock { rows: InboxRow[]; shipments: unknown[]; snoozes: unknown[] }

async function mockInbox(page: Page): Promise<InboxMock> {
  const mock: InboxMock = { rows: [READY, QUESTION, LOW_STOCK], shipments: [], snoozes: [] }
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
    await expect(page.getByTestId('inbox-detail-action')).toHaveCount(0) // 재고 임박은 원래 화면으로

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
})
