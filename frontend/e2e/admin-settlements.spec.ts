import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 정산(Track 85 FE) E2E. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 정산·셀러 API는 page.route로 mock해 로컬 DB를
 * 바꾸지 않고 결정적으로 검증한다(목록 렌더·월 변경→URL→API 파라미터·합계·빈 상태·에러 재시도 / 생성 성공·409 / 상세 렌더·탭 전환·주문 링크 /
 * 확정·지급완료·재생성 성공 / 계좌 미등록·음수 비활성·삭제만 / 셀러별 이력).
 */
const SELLER_A = 'slr_E2E0000000000000000000A01'
const SELLER_B = 'slr_E2E0000000000000000000B02'
const STL_PENDING = 9101
const STL_CONFIRMED = 9102
const STL_NEGATIVE = 9103
const STL_NO_ACCOUNT = 9104
const STL_PAID = 9105
const STL_REGENERATED = 9109
const ORDER_PID = 'ord_E2E0000000000000000000001'

const base = (id: number, sellerPublicId: string, companyName: string, overrides: Record<string, unknown> = {}) => ({
  id,
  seller: { publicId: sellerPublicId, companyName },
  periodStart: '2026-06-01T00:00:00+09:00',
  periodEnd: '2026-06-30T23:59:59.999999+09:00',
  grossAmount: 100_000,
  feeAmount: 10_000,
  refundAmount: 20_000,
  netAmount: 70_000,
  status: 'PENDING',
  scheduledPayDate: '2026-07-20',
  bankAccountRegistered: true,
  saleItemCount: 2,
  ...overrides,
})

const ROW_PENDING = base(STL_PENDING, SELLER_A, 'E2E셀러A')
const ROW_CONFIRMED = base(STL_CONFIRMED, SELLER_B, 'E2E셀러B', { status: 'CONFIRMED' })
const ROW_NEGATIVE = base(STL_NEGATIVE, SELLER_B, 'E2E셀러B', { status: 'CONFIRMED', refundAmount: 95_000, netAmount: -5_000 })
const ROW_NO_ACCOUNT = base(STL_NO_ACCOUNT, SELLER_A, 'E2E셀러A', { status: 'CONFIRMED', bankAccountRegistered: false })
const ROW_PAID = base(STL_PAID, SELLER_A, 'E2E셀러A', { status: 'PAID', paidAt: '2026-07-21T10:00:00+09:00', periodStart: '2026-05-01T00:00:00+09:00', periodEnd: '2026-05-31T23:59:59.999999+09:00' })
const JUNE_ROWS = [ROW_PENDING, ROW_CONFIRMED, ROW_NEGATIVE]
const TOTALS = { grossAmount: 300_000, feeAmount: 30_000, refundAmount: 135_000, netAmount: 135_000, pendingCount: 1, confirmedCount: 2, paidCount: 0 }
const EMPTY_TOTALS = { grossAmount: 0, feeAmount: 0, refundAmount: 0, netAmount: 0, pendingCount: 0, confirmedCount: 0, paidCount: 0 }

const CONTACT = { contactEmail: 'se***@e2e.invalid', contactPhone: '010-****-1234' }
const BANK_CURRENT = { id: 1, bankCode: '004', accountHolder: '홍길동', accountNumberSuffix: '5678', snapshot: false }
const BANK_SNAPSHOT = { ...BANK_CURRENT, snapshot: true }

function detailOf(row: ReturnType<typeof base>) {
  const bankAccount = row.status === 'PAID' ? BANK_SNAPSHOT : (row.bankAccountRegistered ? BANK_CURRENT : undefined)
  return { ...row, refundItemCount: 1, sellerContact: CONTACT, bankAccount }
}

const SALE_ITEMS = [
  { id: 1, itemType: 'SALE', orderItemId: 11, orderPublicId: ORDER_PID, productName: 'E2E 티셔츠', optionLabel: '색상: 블랙 / 사이즈: M', quantity: 2, amount: 60_000, commissionRate: 1000, feeAmount: 6_000, occurredAt: '2026-06-10T12:00:00+09:00' },
  { id: 2, itemType: 'SALE', orderItemId: 12, orderPublicId: ORDER_PID, productName: 'E2E 바지', quantity: 1, amount: 40_000, commissionRate: 1250, feeAmount: 5_000, occurredAt: '2026-06-12T09:30:00+09:00' },
]
const REFUND_ITEMS = [
  { id: 3, itemType: 'REFUND', orderItemId: 11, refundId: 5, orderPublicId: ORDER_PID, productName: 'E2E 티셔츠', optionLabel: '색상: 블랙 / 사이즈: M', quantity: 1, amount: 20_000, commissionRate: 1000, feeAmount: 0, occurredAt: '2026-06-20T15:00:00+09:00' },
]

interface Captured {
  listFail: boolean
  listQueries: URLSearchParams[]
  itemQueries: URLSearchParams[]
  sellerHistoryUrls: string[]
  writes: { method: string; url: string; body: string }[]
}

async function mockSettlementApi(page: Page, options: { listStatus?: number; createStatus?: number; regenerateDeletedOnly?: boolean } = {}): Promise<Captured> {
  const captured: Captured = { listFail: options.listStatus === 500, listQueries: [], itemQueries: [], sellerHistoryUrls: [], writes: [] }
  const problem = (status: number, code: string, detail: string) =>
    ({ status, contentType: 'application/problem+json', json: { code, detail } })
  const paged = (items: unknown[]) => ({ items, page: 0, size: 20, totalCount: items.length, hasNext: false })
  const detailById = new Map(
    [ROW_PENDING, ROW_CONFIRMED, ROW_NEGATIVE, ROW_NO_ACCOUNT, ROW_PAID].map((row) => [row.id, detailOf(row)]),
  )
  detailById.set(STL_REGENERATED, detailOf(base(STL_REGENERATED, SELLER_A, 'E2E셀러A', { netAmount: 65_000 })))

  await page.route((url) => url.pathname.endsWith('/api/v1/admin/settlements'), (route) => {
    const request = route.request()
    if (request.method() === 'POST') {
      captured.writes.push({ method: 'POST', url: request.url(), body: request.postData() ?? '' })
      if (options.createStatus === 409) return route.fulfill(problem(409, 'SETTLEMENT_ALREADY_EXISTS', '동시 실행 중복'))
      if (options.createStatus === 400) return route.fulfill(problem(400, 'SETTLEMENT_PERIOD_INVALID', '마감 전 기간'))
      return route.fulfill({ status: 201, json: { year: 2026, month: 6, periodStart: ROW_PENDING.periodStart, periodEnd: ROW_PENDING.periodEnd, createdCount: 2, settlements: [] } })
    }
    const query = new URL(request.url()).searchParams
    captured.listQueries.push(query)
    if (captured.listFail) return route.fulfill(problem(500, 'INTERNAL_ERROR', '서버 오류'))
    const isJune = query.get('year') === '2026' && query.get('month') === '6'
    let rows = isJune ? JUNE_ROWS : []
    const status = query.get('status')
    if (status) rows = rows.filter((row) => row.status === status)
    const keyword = query.get('keyword')
    if (keyword) rows = rows.filter((row) => row.seller.companyName.includes(keyword))
    return route.fulfill({ json: { ...paged(rows), totals: isJune ? TOTALS : EMPTY_TOTALS } })
  })
  await page.route((url) => /\/api\/v1\/admin\/settlements\/\d+$/.test(url.pathname), (route) => {
    const id = Number(route.request().url().split('/').pop())
    const detail = detailById.get(id)
    return detail ? route.fulfill({ json: detail }) : route.fulfill(problem(404, 'SETTLEMENT_NOT_FOUND', '정산 없음'))
  })
  await page.route((url) => /\/api\/v1\/admin\/settlements\/\d+\/items$/.test(url.pathname), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.itemQueries.push(query)
    return route.fulfill({ json: paged(query.get('type') === 'REFUND' ? REFUND_ITEMS : SALE_ITEMS) })
  })
  await page.route((url) => /\/api\/v1\/admin\/settlements\/\d+\/(confirm|pay|regenerate)$/.test(url.pathname), (route) => {
    const request = route.request()
    const [id, action] = request.url().split('/').slice(-2)
    captured.writes.push({ method: 'POST', url: request.url(), body: request.postData() ?? '' })
    if (action === 'confirm') {
      detailById.set(Number(id), { ...detailById.get(Number(id))!, status: 'CONFIRMED' })
      return route.fulfill({ json: { settlementId: Number(id), status: 'CONFIRMED' } })
    }
    if (action === 'pay') {
      detailById.set(Number(id), { ...detailById.get(Number(id))!, status: 'PAID', paidAt: '2026-07-21T10:00:00+09:00', bankAccount: BANK_SNAPSHOT })
      return route.fulfill({ json: { settlementId: Number(id), status: 'PAID', paidAt: '2026-07-21T10:00:00+09:00' } })
    }
    if (options.regenerateDeletedOnly) return route.fulfill({ json: { deletedSettlementId: Number(id), deletedOnly: true } })
    return route.fulfill({ json: { deletedSettlementId: Number(id), deletedOnly: false, settlementId: STL_REGENERATED, grossAmount: 100_000, feeAmount: 10_000, refundAmount: 25_000, netAmount: 65_000 } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/sellers'), (route) => route.fulfill({
    json: [
      { sellerPublicId: SELLER_A, companyName: 'E2E셀러A', status: 'ACTIVE' },
      { sellerPublicId: SELLER_B, companyName: 'E2E셀러B', status: 'ACTIVE' },
    ],
  }))
  await page.route((url) => /\/api\/v1\/admin\/sellers\/slr_[^/]+\/settlements$/.test(url.pathname), (route) => {
    const url = route.request().url()
    captured.sellerHistoryUrls.push(url)
    if (url.includes(SELLER_A)) return route.fulfill({ json: paged([ROW_PENDING, ROW_PAID]) })
    return route.fulfill(problem(404, 'SELLER_NOT_FOUND', '셀러 없음'))
  })
  return captured
}

async function gotoPath(page: Page, path: string): Promise<void> {
  // /admin/** 은 CSR 전용이라 사이드바 이동 대신 goto 후 API 응답을 기다린다.
  await page.goto(path)
  await page.waitForLoadState('networkidle')
}

const JUNE = '/admin/settlements?year=2026&month=6'

test.describe('관리자 정산(Track 85)', () => {
  test('① 목록 렌더(합계·음수 강조·계좌 chip)·기본 월은 지난달 URL·월 변경 → URL·API 파라미터·상태 필터·검색 빈 상태·초기화', async ({ page }) => {
    const captured = await mockSettlementApi(page)
    await loginAs(page, 'ADMIN')
    await gotoPath(page, '/admin/settlements')
    // 기본 월(지난달)이 URL에 실린다
    const now = new Date()
    const expectedMonth = now.getMonth() === 0 ? 12 : now.getMonth()
    await page.waitForURL((url) => url.searchParams.get('month') === String(expectedMonth) && url.searchParams.has('year'))
    expect(captured.listQueries[0]?.get('month')).toBe(String(expectedMonth))

    await gotoPath(page, JUNE)
    await expect(page.getByTestId('row-seller')).toHaveCount(3)
    await expect(page.getByTestId('admin-stat-card-value').first()).toHaveText('300,000원')
    await expect(page.getByTestId('admin-settlement-totals')).toContainText('대기 1 · 확정 2 · 지급 0')
    await expect(page.getByTestId('row-net').nth(2)).toHaveText('-5,000원')
    await expect(page.getByTestId('row-net').nth(2)).toHaveClass(/text-error/)
    await expect(page.getByTestId('row-bank').first()).toHaveText('등록')
    expect(captured.listQueries.at(-1)?.get('year')).toBe('2026')
    expect(captured.listQueries.at(-1)?.get('month')).toBe('6')

    await page.getByTestId('filter-status').click()
    await page.getByRole('option', { name: '확정', exact: true }).click()
    await page.waitForURL((url) => url.searchParams.get('status') === 'CONFIRMED')
    await expect(page.getByTestId('row-seller')).toHaveCount(2)
    expect(captured.listQueries.at(-1)?.get('status')).toBe('CONFIRMED')

    await page.getByTestId('filter-keyword').locator('input').fill('없는셀러')
    await page.getByTestId('filter-search').click()
    await page.waitForURL((url) => url.searchParams.get('keyword') === '없는셀러')
    await expect(page.getByTestId('admin-settlement-empty')).toContainText('조건에 맞는 정산이 없습니다')
    expect(captured.listQueries.at(-1)?.get('keyword')).toBe('없는셀러')

    await page.getByTestId('filter-reset').click()
    await page.waitForURL((url) => !url.searchParams.has('keyword') && !url.searchParams.has('status') && url.searchParams.get('month') === '6')
    await expect(page.getByTestId('row-seller')).toHaveCount(3)

    await page.getByTestId('filter-month').click()
    await page.getByRole('option', { name: '7월', exact: true }).click()
    await page.waitForURL((url) => url.searchParams.get('month') === '7')
    await expect(page.getByTestId('admin-settlement-empty')).toContainText('2026년 7월 정산이 없습니다')
    await expect(page.getByTestId('admin-settlement-empty')).toContainText('정산은 매월 1일 이후 전월분이 자동 생성됩니다. 과거 월은 상단의 정산 생성으로 만들 수 있습니다.')
    expect(captured.listQueries.at(-1)?.get('month')).toBe('7')
  })

  test('② 목록 에러 → 다시 시도로 복구 / 정산 생성 409 → 문구 토스트 / 생성 성공 → POST body·성공 토스트·재조회', async ({ page }) => {
    const captured = await mockSettlementApi(page, { listStatus: 500, createStatus: 409 })
    await loginAs(page, 'ADMIN')
    await gotoPath(page, JUNE)
    await expect(page.getByTestId('admin-settlement-error')).toBeVisible()
    captured.listFail = false
    await page.getByTestId('admin-settlement-retry').click()
    await expect(page.getByTestId('row-seller')).toHaveCount(3)

    await page.getByTestId('settlement-create').click()
    await expect(page.getByTestId('settlement-create-dialog')).toContainText('2026년 6월 정산을 생성합니다')
    await page.getByTestId('settlement-create-dialog-ok').click()
    await expect(page.getByText('같은 기간의 정산이 이미 생성되고 있습니다. 잠시 후 목록을 새로고침하세요.')).toBeVisible()

    await page.unrouteAll({ behavior: 'ignoreErrors' })
    const second = await mockSettlementApi(page)
    await page.getByTestId('settlement-create').click()
    await page.getByTestId('settlement-create-dialog-ok').click()
    await expect(page.getByText('2026년 6월 정산 2건을 생성했습니다.')).toBeVisible()
    const post = second.writes.find((write) => write.url.endsWith('/api/v1/admin/settlements'))
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ year: 2026, month: 6 })
    expect(second.listQueries.length).toBeGreaterThanOrEqual(1) // 생성 후 재조회
  })

  test('③ 상세 렌더(헤더·연락처·현재 계좌) → 환불 탭(type REFUND·URL tab·수수료 0) → 판매 탭 수수료율 % → 주문번호 클릭 → 주문 상세(back=정산 상세) → 목록 back 복귀', async ({ page }) => {
    const captured = await mockSettlementApi(page)
    await loginAs(page, 'ADMIN')
    await gotoPath(page, JUNE)
    await page.getByTestId('row-open').first().click()
    await page.waitForURL((url) => url.pathname === `/admin/settlements/${STL_PENDING}`)
    await expect(page.getByTestId('settlement-seller')).toHaveText('E2E셀러A')
    await expect(page.getByTestId('settlement-status')).toHaveText('확정 대기')
    await expect(page.getByTestId('settlement-net')).toHaveText('70,000원')
    await expect(page.getByTestId('settlement-scheduled')).toHaveText('2026.07.20')
    await expect(page.getByTestId('settlement-contact-phone')).toHaveText('010-****-1234')
    await expect(page.getByTestId('settlement-bank-account')).toHaveText('004 ···5678 (홍길동)')
    await expect(page.getByTestId('settlement-bank-source')).toHaveText('현재 주 정산계좌')
    await expect(page.getByTestId('action-confirm')).toBeVisible()
    await expect(page.getByTestId('action-regenerate')).toBeVisible()
    await expect(page.getByTestId('action-pay')).toHaveCount(0)
    await expect(page.getByTestId('item-product')).toHaveCount(2)
    await expect(page.getByTestId('item-rate').nth(1)).toHaveText('12.5%')
    expect(captured.itemQueries[0]?.get('type')).toBe('SALE')

    await page.getByTestId('settlement-tab-REFUND').click()
    await page.waitForURL((url) => url.searchParams.get('tab') === 'REFUND')
    await expect(page.getByTestId('item-product')).toHaveCount(1)
    await expect(page.getByTestId('item-fee')).toHaveText('0원')
    expect(captured.itemQueries.at(-1)?.get('type')).toBe('REFUND')

    await page.route((url) => /\/api\/v1\/admin\/orders\/ord_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: { orderId: ORDER_PID, orderNo: 'ORD-20260610-0001', orderedAt: '2026-06-10T12:00:00', status: 'CONFIRMED', buyer: { userId: 'usr_x', name: '구매자', email: 'b@e2e.invalid' }, totalPrice: 60000, discountAmount: 0, shippingFee: 0, paymentAmount: 60000, payments: [], items: [], cancelReasons: [], actions: [] } }))
    await page.getByTestId('item-order-no').first().click()
    await page.waitForURL((url) => url.pathname === `/admin/orders/${ORDER_PID}`)
    const orderUrl = decodeURIComponent(page.url())
    expect(orderUrl).toContain(`back=/admin/settlements/${STL_PENDING}?`)
    expect(orderUrl).toContain('tab=REFUND')

    await gotoPath(page, `/admin/settlements/${STL_PENDING}?back=${encodeURIComponent('/admin/settlements?year=2026&month=6&status=PENDING')}`)
    await page.getByTestId('settlement-back').click()
    await page.waitForURL((url) => url.pathname === '/admin/settlements' && url.searchParams.get('status') === 'PENDING')
  })

  test('④ 확정 → POST confirm·확정 상태·지급완료 버튼 / 지급완료 → POST pay·지급일·스냅샷 계좌·액션 없음 / 재생성 → 사유 필수·POST body·새 정산으로 이동', async ({ page }) => {
    const captured = await mockSettlementApi(page)
    await loginAs(page, 'ADMIN')
    await gotoPath(page, `/admin/settlements/${STL_PENDING}`)
    await page.getByTestId('action-confirm').click()
    await expect(page.getByTestId('settlement-confirm-dialog')).toContainText('SMS가 발송')
    await page.getByTestId('settlement-confirm-dialog-ok').click()
    await expect(page.getByTestId('settlement-status')).toHaveText('확정')
    expect(captured.writes.some((write) => write.url.endsWith(`/${STL_PENDING}/confirm`))).toBe(true)
    await expect(page.getByTestId('action-pay')).toBeEnabled()
    await expect(page.getByTestId('action-confirm')).toHaveCount(0)

    await page.getByTestId('action-pay').click()
    await expect(page.getByTestId('settlement-pay-dialog')).toContainText('004 ···5678')
    await page.getByTestId('settlement-pay-dialog-ok').click()
    await expect(page.getByTestId('settlement-status')).toHaveText('지급완료')
    await expect(page.getByTestId('settlement-paid-at')).toHaveText('2026.07.21 10:00')
    await expect(page.getByTestId('settlement-bank-source')).toHaveText('지급 시점 계좌(스냅샷)')
    await expect(page.getByTestId('settlement-paid-notice')).toBeVisible()
    await expect(page.getByTestId('action-pay')).toHaveCount(0)
    expect(captured.writes.some((write) => write.url.endsWith(`/${STL_PENDING}/pay`))).toBe(true)

    await page.unrouteAll({ behavior: 'ignoreErrors' })
    const second = await mockSettlementApi(page)
    await gotoPath(page, `/admin/settlements/${STL_PENDING}?back=${encodeURIComponent(JUNE)}`)
    await page.getByTestId('action-regenerate').click()
    await expect(page.getByTestId('settlement-regenerate-dialog')).toBeVisible()
    await expect(page.getByTestId('settlement-regenerate-dialog-ok')).toBeDisabled()
    await page.getByTestId('settlement-regenerate-reason').locator('textarea:not(.v-textarea__sizer)').fill('반품 완료 누락분 반영')
    await page.getByTestId('settlement-regenerate-dialog-ok').click()
    await page.waitForURL((url) => url.pathname === `/admin/settlements/${STL_REGENERATED}`)
    await expect(page.getByTestId('settlement-net')).toHaveText('65,000원')
    const regenerate = second.writes.find((write) => write.url.endsWith(`/${STL_PENDING}/regenerate`))
    expect(JSON.parse(regenerate?.body ?? '{}')).toEqual({ reason: '반품 완료 누락분 반영' })
    expect(decodeURIComponent(page.url())).toContain(`back=${JUNE}`)
  })

  test('⑤ 지급액 음수 → 지급 비활성 + 사유 / 계좌 미등록 → 비활성 + 안내 / 재생성 삭제만 → 목록 이동 / 미존재 → 404 화면', async ({ page }) => {
    await mockSettlementApi(page, { regenerateDeletedOnly: true })
    await loginAs(page, 'ADMIN')
    await gotoPath(page, `/admin/settlements/${STL_NEGATIVE}`)
    await expect(page.getByTestId('action-pay')).toBeDisabled()
    await expect(page.getByTestId('settlement-pay-blocked')).toContainText('음수')
    await expect(page.getByTestId('settlement-net')).toHaveClass(/text-error/)

    await gotoPath(page, `/admin/settlements/${STL_NO_ACCOUNT}`)
    await expect(page.getByTestId('action-pay')).toBeDisabled()
    await expect(page.getByTestId('settlement-pay-blocked')).toContainText('정산계좌')
    await expect(page.getByTestId('settlement-bank-missing')).toBeVisible()

    await gotoPath(page, `/admin/settlements/${STL_PENDING}?back=${encodeURIComponent(JUNE)}`)
    await page.getByTestId('action-regenerate').click()
    await page.getByTestId('settlement-regenerate-reason').locator('textarea:not(.v-textarea__sizer)').fill('테스트 삭제')
    await page.getByTestId('settlement-regenerate-dialog-ok').click()
    await page.waitForURL((url) => url.pathname === '/admin/settlements' && url.searchParams.get('month') === '6')
    await expect(page.getByText('재집계 대상(구매확정 매출)이 없어 새 정산은 만들지 않았습니다.')).toBeVisible()

    await gotoPath(page, '/admin/settlements/999999')
    await expect(page.getByTestId('settlement-not-found')).toBeVisible()
  })

  test('⑥ 셀러별 정산: 미선택 안내 → 셀러 선택 → URL seller·이력 API·기간 컬럼·지급일 → 상세(back=셀러별) → 복귀 / 미존재 셀러 404 안내', async ({ page }) => {
    const captured = await mockSettlementApi(page)
    await loginAs(page, 'ADMIN')
    await gotoPath(page, '/admin/settlements/sellers')
    await expect(page.getByTestId('seller-unselected')).toBeVisible()
    await page.getByTestId('seller-select').click()
    await page.getByRole('option', { name: 'E2E셀러A', exact: true }).click()
    await page.waitForURL((url) => url.searchParams.get('seller') === SELLER_A)
    await expect(page.getByTestId('row-period')).toHaveCount(2)
    await expect(page.getByTestId('row-period').first()).toHaveText('2026년 6월')
    await expect(page.getByTestId('row-paid-at').nth(1)).toHaveText('2026.07.21 10:00')
    expect(captured.sellerHistoryUrls[0]).toContain(`/api/v1/admin/sellers/${SELLER_A}/settlements`)

    await page.getByTestId('row-open').first().click()
    await page.waitForURL((url) => url.pathname === `/admin/settlements/${STL_PENDING}`)
    expect(decodeURIComponent(page.url())).toContain(`back=/admin/settlements/sellers?seller=${SELLER_A}`)
    await page.getByTestId('settlement-back').click()
    await page.waitForURL((url) => url.pathname === '/admin/settlements/sellers' && url.searchParams.get('seller') === SELLER_A)
    await expect(page.getByTestId('row-period')).toHaveCount(2)

    await gotoPath(page, `/admin/settlements/sellers?seller=${SELLER_B}`)
    await expect(page.getByTestId('seller-not-found')).toBeVisible()
  })
})
