import { test, expect, type Page } from '@playwright/test'

/**
 * 관리자 회원 관리(Track 84 FE) E2E. 로그인은 데모 버튼(NUXT_ADMIN_DEMO_* 주입 환경·미주입 시 skip), 회원·주문·클레임 API는 page.route로
 * mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(목록 렌더·검색→URL→API 파라미터·빈 상태·에러 재시도·탈퇴회원 탈퇴일 컬럼·상세·탭 파라미터·
 * 수정·등급 변경·탈퇴 409·임시 비밀번호 204/502·탈퇴 회원 액션 비활성).
 */
const MEMBER_A = 'usr_E2E0000000000000000000A01'
const MEMBER_B = 'usr_E2E0000000000000000000B02'
const MEMBER_W = 'usr_E2E0000000000000000000W03'

const ACTIVE_ROWS = [
  { publicId: MEMBER_A, name: 'E2E회원A', email: 'a@e2e.invalid', phone: '010-1111-2222', gradeCode: 'SILVER', createdAt: '2026-09-10T10:00:00', lastPaidAt: '2026-09-15T12:30:00' },
  { publicId: MEMBER_B, name: 'E2E회원B', email: 'b@e2e.invalid', gradeCode: 'GOLD', createdAt: '2026-09-01T09:00:00' },
]
const WITHDRAWN_ROWS = [
  { publicId: MEMBER_W, name: 'E2E탈퇴회원', email: 'w@e2e.invalid', phone: '010-9999-8888', gradeCode: 'SILVER', createdAt: '2026-08-01T09:00:00', withdrawnAt: '2026-09-05T08:00:00' },
]

const DETAIL_A = {
  publicId: MEMBER_A, name: 'E2E회원A', email: 'a@e2e.invalid', phone: '010-1111-2222', createdAt: '2026-09-10T10:00:00',
  passwordChangeRequired: false,
  grade: { code: 'SILVER', source: 'AUTO' },
  addresses: [{ id: 1, isDefault: true, addressLabel: '집', recipientName: '수령인A', recipientPhone: '010-1111-2222', zonecode: '06236', addressRoad: '서울 강남구 테헤란로 1', addressDetail: '101호' }],
}
const DETAIL_B_NO_PHONE = { publicId: MEMBER_B, name: 'E2E회원B', email: 'b@e2e.invalid', createdAt: '2026-09-01T09:00:00', passwordChangeRequired: true, grade: { code: 'GOLD', source: 'MANUAL', lockedUntil: '2026-10-01T23:59:59' }, addresses: [] }
const DETAIL_W = { ...DETAIL_A, publicId: MEMBER_W, name: 'E2E탈퇴회원', email: 'w@e2e.invalid', withdrawnAt: '2026-09-05T08:00:00' }

const ORDER_ROW = {
  orderId: 'ord_E2E0000000000000000000001', orderNo: 'ORD-20260915-0001', orderedAt: '2026-09-15T12:00:00', paidAt: '2026-09-15T12:30:00', status: 'PAID',
  buyerName: 'E2E회원A', buyerEmail: 'a@e2e.invalid', sellerNames: ['E2E셀러'], productSummary: 'E2E 티셔츠', itemCount: 1,
  paymentAmount: 19900, shippingFee: 0, paymentMethod: 'CARD', paymentStatus: 'PAID', claimInProgress: false, actions: [],
}
const CLAIM_ROW = (type: string) => ({
  claimId: `clm_E2E000000000000000000${type.slice(0, 3)}`, type, status: 'REQUESTED', requestedAt: '2026-09-16T11:00:00',
  orderId: 'ord_E2E0000000000000000000001', orderNo: 'ORD-20260915-0001', buyerName: 'E2E회원A', productName: 'E2E 티셔츠', quantity: 1, amount: 19900,
  reasonCode: 'BUYER_CHANGED_MIND', availableActions: [],
})

interface Captured {
  /** true인 동안 목록 API가 500을 돌려준다(에러 → 다시 시도 검증용·테스트가 직접 끈다). */
  listFail: boolean
  listQueries: URLSearchParams[]
  orderQueries: URLSearchParams[]
  claimQueries: URLSearchParams[]
  writes: { method: string; url: string; body: string }[]
}

async function mockMemberApi(page: Page, options: { listStatus?: number; withdrawStatus?: number; resetStatus?: number } = {}): Promise<Captured> {
  const captured: Captured = { listFail: options.listStatus === 500, listQueries: [], orderQueries: [], claimQueries: [], writes: [] }
  const problem = (status: number, code: string, detail: string) =>
    ({ status, contentType: 'application/problem+json', json: { code, detail } })

  await page.route((url) => url.pathname.endsWith('/api/v1/admin/members'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    if (captured.listFail) return route.fulfill(problem(500, 'INTERNAL_ERROR', '서버 오류'))
    const rows = query.get('status') === 'WITHDRAWN' ? WITHDRAWN_ROWS : ACTIVE_ROWS
    const keyword = query.get('keyword')
    const filtered = keyword ? rows.filter((row) => row.name.includes(keyword) || row.email.includes(keyword) || (row.phone ?? '').includes(keyword)) : rows
    return route.fulfill({ json: { items: filtered, page: 0, size: 20, totalCount: filtered.length, hasNext: false } })
  })
  await page.route((url) => /\/api\/v1\/admin\/members\/usr_[^/]+$/.test(url.pathname), (route) => {
    const request = route.request()
    if (request.method() === 'PATCH') {
      captured.writes.push({ method: 'PATCH', url: request.url(), body: request.postData() ?? '' })
      return route.fulfill({ status: 204 })
    }
    const id = request.url().split('/').pop() ?? ''
    if (id.startsWith(MEMBER_W)) return route.fulfill({ json: DETAIL_W })
    if (id.startsWith(MEMBER_B)) return route.fulfill({ json: DETAIL_B_NO_PHONE })
    if (id.startsWith(MEMBER_A)) return route.fulfill({ json: DETAIL_A })
    return route.fulfill(problem(404, 'USER_NOT_FOUND', '회원을 찾을 수 없습니다'))
  })
  await page.route((url) => /\/api\/v1\/admin\/members\/usr_[^/]+\/withdraw$/.test(url.pathname), (route) => {
    captured.writes.push({ method: 'POST', url: route.request().url(), body: '' })
    if (options.withdrawStatus === 409) return route.fulfill(problem(409, 'MEMBER_ACTIVITY_IN_PROGRESS', '진행 중인 주문이 있어 탈퇴할 수 없습니다'))
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => /\/api\/v1\/admin\/members\/usr_[^/]+\/password-reset$/.test(url.pathname), (route) => {
    captured.writes.push({ method: 'POST', url: route.request().url(), body: '' })
    if (options.resetStatus === 502) return route.fulfill(problem(502, 'TEMPORARY_PASSWORD_DELIVERY_FAILED', 'SMS 발송 실패'))
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => /\/api\/v1\/admin\/members\/usr_[^/]+\/grade$/.test(url.pathname), (route) => {
    captured.writes.push({ method: 'PUT', url: route.request().url(), body: route.request().postData() ?? '' })
    return route.fulfill({ status: 204 })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/orders'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.orderQueries.push(query)
    return route.fulfill({ json: { items: [ORDER_ROW], page: 0, size: 20, totalCount: 1, hasNext: false } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/claims'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.claimQueries.push(query)
    const type = query.get('type') ?? 'CANCEL'
    const items = type === 'EXCHANGE' ? [] : [CLAIM_ROW(type)]
    return route.fulfill({ json: { items, page: 0, size: 20, totalCount: items.length, hasNext: false, pendingCount: items.length } })
  })
  return captured
}

async function loginByDemo(page: Page): Promise<void> {
  await page.goto('/admin/login')
  await page.waitForLoadState('networkidle')
  const demoButton = page.getByTestId('admin-demo-login')
  test.skip((await demoButton.count()) === 0, 'NUXT_ADMIN_DEMO_EMAIL/PASSWORD 미주입 — 데모 버튼 없음')
  await demoButton.click()
  await page.waitForURL(/\/admin$/)
}

async function gotoMembers(page: Page, path = '/admin/members'): Promise<void> {
  // /admin/** 은 CSR 전용이라 사이드바 이동 대신 goto 후 목록 API 응답을 기다린다.
  await page.goto(path)
  await page.waitForLoadState('networkidle')
}

test.describe('관리자 회원 관리(Track 84)', () => {
  test('① 목록 렌더(번호 역순·최종구매일 -)·검색 → URL·API 파라미터(status ACTIVE·keyword)·빈 상태 문구·초기화', async ({ page }) => {
    const captured = await mockMemberApi(page)
    await loginByDemo(page)
    await gotoMembers(page)
    await expect(page.getByTestId('admin-member-table')).toBeVisible()
    await expect(page.getByTestId('row-name')).toHaveCount(2)
    await expect(page.getByTestId('row-number').first()).toHaveText('2')
    await expect(page.getByTestId('row-number').nth(1)).toHaveText('1')
    await expect(page.getByTestId('row-last-paid-at').first()).toHaveText('2026.09.15 12:30')
    await expect(page.getByTestId('row-last-paid-at').nth(1)).toHaveText('-')
    expect(captured.listQueries[0]?.get('status')).toBe('ACTIVE')
    expect(captured.listQueries[0]?.get('sort')).toBe('LATEST')

    await page.getByTestId('filter-keyword').locator('input').fill('없는회원')
    await page.getByTestId('filter-search').click()
    await page.waitForURL((url) => url.searchParams.get('keyword') === '없는회원')
    await expect(page.getByTestId('admin-member-empty')).toContainText('조건에 맞는 회원이 없습니다')
    expect(captured.listQueries.at(-1)?.get('keyword')).toBe('없는회원')

    await page.getByTestId('filter-reset').click()
    await page.waitForURL((url) => !url.searchParams.has('keyword'))
    await expect(page.getByTestId('row-name')).toHaveCount(2)
  })

  test('② 목록 에러 → 다시 시도로 복구 / 탈퇴회원 목록은 status WITHDRAWN·탈퇴일 컬럼', async ({ page }) => {
    const captured = await mockMemberApi(page, { listStatus: 500 })
    await loginByDemo(page)
    await gotoMembers(page)
    await expect(page.getByTestId('admin-member-error')).toBeVisible()
    captured.listFail = false
    await page.getByTestId('admin-member-retry').click()
    await expect(page.getByTestId('row-name')).toHaveCount(2)

    await gotoMembers(page, '/admin/members/withdrawn')
    await expect(page.getByTestId('row-name')).toHaveCount(1)
    await expect(page.getByTestId('row-withdrawn-at')).toHaveText('2026.09.05 08:00')
    expect(captured.listQueries.at(-1)?.get('status')).toBe('WITHDRAWN')
  })

  test('③ 상세 렌더(정보·등급·배송지) → 탭 전환 요청 파라미터(buyerPublicId·type)·URL tab·교환 빈 상태 → 주문번호 클릭 → 주문 상세(back=회원 상세)', async ({ page }) => {
    const captured = await mockMemberApi(page)
    await loginByDemo(page)
    await gotoMembers(page)
    await page.getByTestId('row-open').first().click()
    await page.waitForURL((url) => url.pathname === `/admin/members/${MEMBER_A}`)
    await expect(page.getByTestId('member-name')).toHaveText('E2E회원A')
    await expect(page.getByTestId('member-grade-code')).toHaveText('실버')
    await expect(page.getByTestId('member-grade-source')).toHaveText('자동')
    await expect(page.getByTestId('member-address-row')).toHaveCount(1)
    await expect(page.getByTestId('activity-order-no')).toHaveText('ORD-20260915-0001')
    expect(captured.orderQueries[0]?.get('buyerPublicId')).toBe(MEMBER_A)

    await page.getByTestId('member-tab-return').click()
    await page.waitForURL((url) => url.searchParams.get('tab') === 'return')
    await expect(page.getByTestId('activity-status-chip')).toHaveText('요청')
    expect(captured.claimQueries.at(-1)?.get('buyerPublicId')).toBe(MEMBER_A)
    expect(captured.claimQueries.at(-1)?.get('type')).toBe('RETURN')

    await page.getByTestId('member-tab-exchange').click()
    await page.waitForURL((url) => url.searchParams.get('tab') === 'exchange')
    await expect(page.getByTestId('member-activity-empty')).toContainText('교환 내역이 없습니다')
    expect(captured.claimQueries.at(-1)?.get('type')).toBe('EXCHANGE')

    await page.getByTestId('member-tab-orders').click()
    await page.waitForURL((url) => !url.searchParams.has('tab'))
    await page.route((url) => /\/api\/v1\/admin\/orders\/ord_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: { ...ORDER_ROW, buyer: { userId: MEMBER_A, name: 'E2E회원A', email: 'a@e2e.invalid' }, totalPrice: 19900, discountAmount: 0, shippingFee: 0, payments: [], items: [], cancelReasons: [], actions: [] } }))
    await page.getByTestId('activity-order-no').click()
    await page.waitForURL((url) => url.pathname === `/admin/orders/${ORDER_ROW.orderId}`)
    expect(decodeURIComponent(page.url())).toContain(`back=/admin/members/${MEMBER_A}`)
  })

  test('④ 정보 수정(형식 오류 → 저장 안 됨·정상 → PATCH body·재조회) / 등급 변경(PUT body·과거 날짜 오류)', async ({ page }) => {
    const captured = await mockMemberApi(page)
    await loginByDemo(page)
    await gotoMembers(page, `/admin/members/${MEMBER_A}`)
    await expect(page.getByTestId('member-name')).toHaveText('E2E회원A')

    await page.getByTestId('action-edit').click()
    await expect(page.getByTestId('admin-member-edit-dialog')).toBeVisible()
    await page.getByTestId('member-edit-phone').locator('input').fill('02-123-4567')
    await page.getByTestId('member-edit-ok').click()
    await expect(page.getByTestId('admin-member-edit-dialog')).toContainText('휴대폰 번호 형식')
    expect(captured.writes.filter((write) => write.method === 'PATCH')).toHaveLength(0)
    await page.getByTestId('member-edit-name').locator('input').fill('변경이름')
    await page.getByTestId('member-edit-phone').locator('input').fill('010-3333-4444')
    await page.getByTestId('member-edit-ok').click()
    await expect(page.getByTestId('admin-member-edit-dialog')).toBeHidden()
    const patch = captured.writes.find((write) => write.method === 'PATCH')
    expect(JSON.parse(patch?.body ?? '{}')).toEqual({ name: '변경이름', phone: '010-3333-4444' })

    await page.getByTestId('action-grade').click()
    await expect(page.getByTestId('admin-member-grade-dialog')).toBeVisible()
    await page.getByTestId('member-grade-locked-until').locator('input').fill('2020-01-01')
    await page.getByTestId('member-grade-ok').click()
    await expect(page.getByTestId('admin-member-grade-dialog')).toContainText('오늘 이후')
    await page.getByTestId('member-grade-select').click()
    await page.getByRole('option', { name: '플래티넘', exact: true }).click()
    await page.getByTestId('member-grade-locked-until').locator('input').fill('2099-12-31')
    await page.getByTestId('member-grade-ok').click()
    await expect(page.getByTestId('admin-member-grade-dialog')).toBeHidden()
    const put = captured.writes.find((write) => write.method === 'PUT')
    expect(JSON.parse(put?.body ?? '{}')).toEqual({ gradeCode: 'PLATINUM', lockedUntil: '2099-12-31' })
  })

  test('⑤ 탈퇴 409 → 문구 토스트·상세 유지 / 임시 비밀번호 발급 204 → 성공 토스트 / 502 → 실패 문구', async ({ page }) => {
    await mockMemberApi(page, { withdrawStatus: 409, resetStatus: 502 })
    await loginByDemo(page)
    await gotoMembers(page, `/admin/members/${MEMBER_A}`)
    await page.getByTestId('action-withdraw').click()
    await page.getByTestId('member-withdraw-dialog-ok').click()
    await expect(page.getByText('진행 중인 주문 또는 클레임이 있어 탈퇴할 수 없습니다.')).toBeVisible()
    await expect(page).toHaveURL(new RegExp(`/admin/members/${MEMBER_A}`))

    await page.getByTestId('action-reset-password').click()
    await page.getByTestId('member-reset-dialog-ok').click()
    await expect(page.getByText('SMS 발송에 실패했습니다. 비밀번호는 변경되지 않았습니다.')).toBeVisible()

    await page.unrouteAll({ behavior: 'ignoreErrors' })
    await mockMemberApi(page)
    await page.getByTestId('action-reset-password').click()
    await page.getByTestId('member-reset-dialog-ok').click()
    await expect(page.getByText('임시 비밀번호를 SMS로 발송했습니다. 기존 로그인 세션은 종료됩니다.')).toBeVisible()
  })

  test('⑥ 탈퇴 회원 상세 → 안내 + 액션 4종 비활성 / 연락처 없는 회원 → 발급 버튼 비활성 + 안내·변경 필요 chip / 미존재 → 404 화면·목록 이동', async ({ page }) => {
    await mockMemberApi(page)
    await loginByDemo(page)
    await gotoMembers(page, `/admin/members/${MEMBER_W}?back=${encodeURIComponent('/admin/members/withdrawn')}`)
    await expect(page.getByTestId('member-withdrawn-notice')).toBeVisible()
    for (const action of ['action-edit', 'action-withdraw', 'action-reset-password', 'action-grade']) {
      await expect(page.getByTestId(action)).toBeDisabled()
    }
    await page.getByTestId('member-back').click()
    await page.waitForURL((url) => url.pathname === '/admin/members/withdrawn')

    await gotoMembers(page, `/admin/members/${MEMBER_B}`)
    await expect(page.getByTestId('action-reset-password')).toBeDisabled()
    await expect(page.getByTestId('member-phone-missing')).toBeVisible()
    await expect(page.getByTestId('member-password-change-required')).toBeVisible()
    await expect(page.getByTestId('action-edit')).toBeEnabled()

    await gotoMembers(page, '/admin/members/usr_E2E00000000000000000NONE')
    await expect(page.getByTestId('member-not-found')).toBeVisible()
    await page.getByRole('link', { name: '목록으로' }).click()
    await page.waitForURL((url) => url.pathname === '/admin/members')
  })
})
