import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 주문 목록·상세(FE-27) E2E. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 주문 API는 page.route로 mock해
 * 로컬 DB를 바꾸지 않고 결정적으로 검증한다(목록 렌더·필터→URL→API 파라미터·상세 이동/복귀·부분 취소·미결제 취소 409·송장 등록·클레임 승인·
 * FE-28 거부 사유 다이얼로그·송장 422 활성 클레임).
 */
const PAID_ID = 'ord_E2E0000000000000000000001'
const UNPAID_ID = 'ord_E2E0000000000000000000002'

const SUMMARIES = [
  {
    orderId: PAID_ID, orderNo: 'ORD-20260916-0001', orderedAt: '2026-09-16T10:00:00', paidAt: '2026-09-16T10:05:00', status: 'PAID',
    buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid', sellerNames: ['E2E셀러', 'B셀러'], productSummary: 'E2E 티셔츠 외 1건', itemCount: 2,
    paymentAmount: 32900, shippingFee: 3000, paymentMethod: 'CARD', paymentStatus: 'PAID', deliveryStatus: null, claimInProgress: false,
    actions: ['CANCEL', 'PREPARE_SHIPMENT'],
  },
  {
    orderId: UNPAID_ID, orderNo: 'ORD-20260916-0002', orderedAt: '2026-09-15T09:00:00', status: 'PENDING_PAYMENT',
    buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid', sellerNames: ['E2E셀러'], productSummary: 'E2E 모자', itemCount: 1,
    paymentAmount: 12000, shippingFee: 0, paymentMethod: 'KAKAO', paymentStatus: 'PENDING', deliveryStatus: null, claimInProgress: true,
    actions: ['CANCEL'],
  },
]

const PAID_DETAIL = {
  orderId: PAID_ID, orderNo: 'ORD-20260916-0001', orderedAt: '2026-09-16T10:00:00', paidAt: '2026-09-16T10:05:00', status: 'PAID',
  buyer: { userId: 'usr_E2E1', name: 'E2E구매자', email: 'buyer@e2e.invalid' },
  shippingAddress: { recipientName: '홍길동', recipientPhone: '010-0000-0000', zonecode: '06236', addressRoad: '서울 강남구 테헤란로 1', addressDetail: '101호' },
  totalPrice: 29900, discountAmount: 0, shippingFee: 3000, paymentAmount: 32900,
  // refundedAmount = amount(전액 환불 완료·PAID 잔존) → C-12 경고 배지·수동 취소 버튼 노출 조건(FE-53)
  payments: [{ paymentId: 'pay_E2E1', method: 'CARD', status: 'PAID', amount: 32900, pgProvider: 'MOCK_PG', pgTid: 'MOCK-TID-0001', paidAt: '2026-09-16T10:05:00', createdAt: '2026-09-16T10:01:00', refundedAmount: 32900 }],
  items: [
    { orderItemId: 'oit_E2E0000000000000000000001', productName: 'E2E 티셔츠', optionLabel: 'M', quantity: 1, unitPrice: 19900, totalPrice: 19900, status: 'PAID', sellerName: 'E2E셀러',
      // FE-28: 거부된 취소 클레임(사유·메모) — 거부 사유 표기 검증용·approvable false
      claims: [{ claimId: 'clm_E2E0000000000000000000009', type: 'CANCEL', status: 'REJECTED', reasonCode: 'BUYER_CHANGED_MIND', requestedAt: '2026-09-15T11:00:00', processedAt: '2026-09-15T12:00:00', approvable: false, rejectReasonCode: 'ALREADY_SHIPPED', rejectMemo: '오전 출고분' }] },
    { orderItemId: 'oit_E2E0000000000000000000002', productName: 'E2E 양말', quantity: 2, unitPrice: 5000, totalPrice: 10000, status: 'PAID', sellerName: 'B셀러',
      // FE-29: 품목 배송 = 최신 발송(검수 불합격 재발송)
      delivery: { deliveryId: 'dlv_E2E2', carrier: 'HANJIN', trackingNo: 'RESHIP-0001', status: 'SHIPPING', shippedAt: '2026-09-12T12:00:00' },
      claims: [
        { claimId: 'clm_E2E0000000000000000000001', type: 'RETURN', status: 'REQUESTED', reasonCode: 'PRODUCT_DEFECT', reasonDetail: '올 풀림', requestedAt: '2026-09-16T11:00:00', approvable: true },
        // FE-29: 검수 불합격 반품(회수 송장·회수 확인·검수 chip·첨부 2·품목 배송 = 재발송)
        { claimId: 'clm_E2E0000000000000000000002', type: 'RETURN', status: 'REJECTED', reasonCode: 'WRONG_PRODUCT', requestedAt: '2026-09-10T11:00:00', processedAt: '2026-09-12T11:00:00', approvable: false,
          rejectReasonCode: 'INSPECTION_FAILED', rejectMemo: '사용 흔적', returnCarrier: 'CJ', returnTrackingNo: 'RTN-0002', pickedUpAt: '2026-09-11T09:00:00', inspectionResult: 'FAIL',
          attachmentUrls: ['/api/v1/files/claims/2026/09/E2E1.png', '/api/v1/files/claims/2026/09/E2E2.png'] },
        // FE-30: 교환 완료(원 옵션 → 교환 옵션 라벨)
        { claimId: 'clm_E2E0000000000000000000003', type: 'EXCHANGE', status: 'COMPLETED', reasonCode: 'PRODUCT_DEFECT', requestedAt: '2026-09-01T11:00:00', processedAt: '2026-09-05T11:00:00', approvable: false,
          returnCarrier: 'CJ', returnTrackingNo: 'RTN-0003', pickedUpAt: '2026-09-03T09:00:00', inspectionResult: 'PASS', restock: true, attachmentUrls: [],
          originalOptionLabel: '색상: 빨강', exchangeOptionLabel: '색상: 파랑' },
      ] },
  ],
  cancelReasons: [], actions: ['CANCEL', 'PREPARE_SHIPMENT'],
}

const UNPAID_DETAIL = {
  orderId: UNPAID_ID, orderNo: 'ORD-20260916-0002', orderedAt: '2026-09-15T09:00:00', status: 'PENDING_PAYMENT',
  buyer: { userId: 'usr_E2E1', name: 'E2E구매자', email: 'buyer@e2e.invalid' },
  totalPrice: 12000, discountAmount: 0, shippingFee: 0, paymentAmount: 12000, payments: [],
  items: [{ orderItemId: 'oit_E2E0000000000000000000003', productName: 'E2E 모자', quantity: 1, unitPrice: 12000, totalPrice: 12000, status: 'ORDERED', sellerName: 'E2E셀러', claims: [] }],
  cancelReasons: [], actions: ['CANCEL'],
}

interface Captured { listQueries: URLSearchParams[]; detailGets: string[]; posts: { url: string; body: string }[] }

async function mockAdminApi(page: Page, options: { cancelStatus?: number; shipmentStatus?: number } = {}): Promise<Captured> {
  const captured: Captured = { listQueries: [], detailGets: [], posts: [] }
  const problem = (status: number, code: string, detail: string) =>
    ({ status, contentType: 'application/problem+json', json: { code, detail } })

  await page.route((url) => /\/api\/v1\/admin\/orders\/ord_[^/]+\/cancel$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    if (options.cancelStatus === 409) return route.fulfill(problem(409, 'OPTIMISTIC_LOCK_FAILURE', '동시 수정 충돌이 발생했습니다.'))
    const unpaid = route.request().url().includes(UNPAID_ID)
    return route.fulfill({ json: unpaid
      ? { orderId: UNPAID_ID, orderStatus: 'PAYMENT_EXPIRED', claims: [] }
      : { orderId: PAID_ID, orderStatus: 'PARTIAL_CANCEL', claims: [{ claimId: 'clm_new', orderItemId: 'oit_E2E0000000000000000000001', status: 'APPROVED' }] } })
  })
  await page.route((url) => /\/api\/v1\/admin\/orders\/items\/oit_[^/]+\/prepare-shipment$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    // FE-28·Track 80 C2: 취소 요청 진행 중 품목은 422 CLAIM_STATE_INVALID
    if (options.shipmentStatus === 422) return route.fulfill(problem(422, 'CLAIM_STATE_INVALID', '진행 중인 클레임이 있어 송장을 등록할 수 없습니다.'))
    return route.fulfill({ json: { deliveryPublicId: 'dlv_new', status: 'SHIPPING', carrier: 'CJ', trackingNo: '1234567890' } })
  })
  await page.route((url) => /\/api\/v1\/admin\/claims\/clm_[^/]+\/(approve|reject)$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    const reject = route.request().url().endsWith('/reject')
    const body = JSON.parse(route.request().postData() ?? '{}') as { reasonCode?: string; memo?: string }
    // Track 80: 승인 응답은 CANCEL이면 Mock 자동 콜백으로 COMPLETED까지 수렴하지만 RETURN은 APPROVED. 거부는 사유·메모를 그대로 되돌린다.
    return route.fulfill({ json: reject
      ? { publicId: 'clm_E2E0000000000000000000001', orderItemPublicId: 'oit_E2E0000000000000000000002', claimType: 'RETURN', status: 'REJECTED', reasonCode: 'PRODUCT_DEFECT', requestedAt: '2026-09-16T11:00:00+09:00', processedAt: '2026-09-16T12:00:00+09:00', rejectReasonCode: body.reasonCode, rejectMemo: body.memo }
      : { publicId: 'clm_E2E0000000000000000000001', orderItemPublicId: 'oit_E2E0000000000000000000002', claimType: 'RETURN', status: 'APPROVED', reasonCode: 'PRODUCT_DEFECT', requestedAt: '2026-09-16T11:00:00+09:00', processedAt: '2026-09-16T12:00:00+09:00' } })
  })
  await page.route((url) => /\/api\/v1\/admin\/orders\/ord_[^/]+$/.test(url.pathname), (route) => {
    captured.detailGets.push(route.request().url())
    return route.fulfill({ json: route.request().url().includes(UNPAID_ID) ? UNPAID_DETAIL : PAID_DETAIL })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/orders'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const filtered = query.get('status') ? SUMMARIES.filter((item) => item.status === query.get('status')) : SUMMARIES
    return route.fulfill({ json: { items: filtered, page: 0, size: 20, totalCount: filtered.length, hasNext: false } })
  })
  return captured
}

/** Vuetify select: 활성화 후 옵션 클릭. */
async function pickOption(page: Page, testId: string, optionName: string): Promise<void> {
  await page.getByTestId(testId).click()
  await page.getByRole('option', { name: optionName, exact: true }).click()
}

test.describe('관리자 주문 목록·상세(FE-27)', () => {
  test('① 목록 렌더(행 2·상태/결제 chip·셀러 외 N·결제일 2줄) → 1440px 가로 스크롤 없음 → 상태·기간 필터 URL 반영·새로고침 유지·API 파라미터(T00:00:00/T23:59:59)', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/orders')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await expect(page.getByTestId('row-paid-at').first()).toHaveText('결제 2026.09.16 10:05')
    await expect(page.getByTestId('row-paid-at').nth(1)).toHaveText('결제 —')
    // 9컬럼 병합 목표: 1440에서 표·본문 모두 가로 스크롤 없음(웹폰트 적용 전 폴백 폰트 폭으로 측정되지 않도록 fonts.ready 대기·병렬 워커 부하 시 12px 오탐)
    await page.evaluate(() => document.fonts.ready)
    const overflow = await page.evaluate(() => {
      const wrapper = document.querySelector('[data-testid="admin-order-table"] .v-table__wrapper') as HTMLElement
      return { table: wrapper.scrollWidth - wrapper.clientWidth, body: document.documentElement.scrollWidth - document.documentElement.clientWidth }
    })
    await page.screenshot({ path: 'playwright-report/fe-27/list-desktop.png' })
    expect(overflow).toEqual({ table: 0, body: 0 })
    await expect(page.getByTestId('status-chip').first()).toHaveText('결제완료')
    await expect(page.getByTestId('status-chip').first()).toHaveClass(/adm-chip--info/)
    await expect(page.getByTestId('status-chip').nth(1)).toHaveText('결제대기')
    await expect(page.getByTestId('payment-status-chip').first()).toHaveClass(/adm-chip--success/)
    await expect(page.getByTestId('claim-chip')).toHaveCount(1)
    await expect(page.getByText('E2E셀러 외 1')).toBeVisible()
    await expect(page.getByTestId('admin-sidebar').getByText('전체 주문')).toBeVisible()

    await pickOption(page, 'filter-status', '결제완료')
    await expect(page).toHaveURL(/status=PAID/)
    await expect(page.getByTestId('status-chip')).toHaveCount(1)
    await page.getByTestId('filter-from').locator('input').fill('2026-09-01')
    await page.getByTestId('filter-to').locator('input').fill('2026-09-16')
    await expect(page).toHaveURL(/from=2026-09-01/)
    await expect(page).toHaveURL(/to=2026-09-16/)

    await page.reload()
    await expect(page).toHaveURL(/status=PAID/)
    await expect(page.getByTestId('status-chip')).toHaveCount(1)
    const last = captured.listQueries.at(-1)
    expect(last?.get('status')).toBe('PAID')
    expect(last?.get('sort')).toBe('LATEST')
    expect(last?.get('from')).toBe('2026-09-01T00:00:00')
    expect(last?.get('to')).toBe('2026-09-16T23:59:59')
  })

  test('② 주문번호 클릭 → 상세(?back=목록 URL) → 주문자·배송지·결제·품목 렌더 → 목록으로 복귀 시 필터 URL 유지', async ({ page }) => {
    await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders?status=PAID')
    await page.getByTestId('row-order-no').first().click()
    await expect(page).toHaveURL(/\/admin\/orders\/ord_E2E0000000000000000000001\?back=/)
    await expect(page.getByTestId('order-status-chip')).toHaveText('결제완료')
    await expect(page.getByTestId('order-buyer')).toContainText('buyer@e2e.invalid')
    await expect(page.getByTestId('order-shipping-address')).toContainText('테헤란로')
    await expect(page.getByTestId('payment-row')).toHaveCount(1)
    await expect(page.getByTestId('order-item')).toHaveCount(2)
    await expect(page.getByTestId('claim-approve')).toHaveCount(1)
    // 브레드크럼은 최장 prefix 메뉴(전체 주문)로 해석
    await expect(page.getByLabel('현재 위치')).toContainText('전체 주문')

    await page.getByTestId('back-to-list').click()
    await expect(page).toHaveURL(/^http:\/\/[^/]+\/admin\/orders\?status=PAID$/)
  })

  test('③ 부분 취소: 품목 체크 해제 1건·사유 선택 → POST cancel body(orderItemPublicIds 1개·reasonCode) → danger 토스트 → 상세 재조회', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/orders/${PAID_ID}`)
    await page.getByTestId('open-cancel').click()
    const dialog = page.getByTestId('admin-order-cancel-dialog')
    await expect(dialog.getByTestId('cancel-dialog-ok')).toHaveText('2개 품목 취소')
    // 검증: 사유 없이 제출 → 필드 에러·요청 0
    await dialog.getByTestId('cancel-dialog-ok').click()
    await expect(dialog).toContainText('취소 사유를 선택하세요.')
    expect(captured.posts).toHaveLength(0)

    await dialog.getByTestId('cancel-item-oit_E2E0000000000000000000002').locator('input').click()
    await expect(dialog.getByTestId('cancel-dialog-ok')).toHaveText('1개 품목 취소')
    await pickOption(page, 'cancel-reason', '재고 지연')
    await dialog.getByTestId('cancel-reason-detail').locator('textarea').first().fill('입고 지연으로 관리자 취소')
    await expect(page.getByRole('listbox')).toHaveCount(0)
    await page.screenshot({ path: 'playwright-report/fe-27/cancel-dialog-desktop.png' })
    await dialog.getByTestId('cancel-dialog-ok').click()

    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toContainText('1개 품목의 취소를 승인')
    const body = JSON.parse(captured.posts[0]!.body)
    expect(captured.posts[0]!.url).toContain(`/admin/orders/${PAID_ID}/cancel`)
    expect(body).toEqual({ reasonCode: 'STOCK_DELAY', reasonDetail: '입고 지연으로 관리자 취소', orderItemPublicIds: ['oit_E2E0000000000000000000001'] })
    await expect(dialog).toBeHidden()
    expect(captured.detailGets.length).toBeGreaterThanOrEqual(2)
  })

  test('④ 미결제 취소: 품목 선택 없이 전체 종료 안내·사유 → 409 → warning 문구(이미 종료/결제 완료) → 상세 재조회', async ({ page }) => {
    const captured = await mockAdminApi(page, { cancelStatus: 409 })
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/orders/${UNPAID_ID}`)
    await expect(page.getByTestId('order-status-chip')).toHaveText('결제대기')
    await page.getByTestId('open-cancel').click()
    const dialog = page.getByTestId('admin-order-cancel-dialog')
    await expect(dialog.getByTestId('cancel-unpaid-notice')).toBeVisible()
    await expect(dialog.getByTestId('cancel-items')).toHaveCount(0)
    await expect(dialog.getByTestId('cancel-dialog-ok')).toHaveText('주문 종료')
    await pickOption(page, 'cancel-reason', '결제 오류')
    await dialog.getByTestId('cancel-dialog-ok').click()

    await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toContainText('이미 종료됐거나 결제가 완료된 주문')
    expect(JSON.parse(captured.posts[0]!.body)).toEqual({ reasonCode: 'PAYMENT_ISSUE' })
    await expect(dialog).toBeHidden()
    expect(captured.detailGets.length).toBeGreaterThanOrEqual(2)
  })

  test('⑤ 목록 행 메뉴 "송장 등록" → 상세 선조회 → 품목 선택·택배사·송장번호 검증 → POST prepare-shipment → info 토스트 → 목록 재조회', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    // 미결제 행(actions CANCEL만)은 상태 변경 메뉴가 없다
    await expect(page.getByTestId('row-menu')).toHaveCount(1)
    await page.getByTestId('row-menu').first().click()
    await page.getByTestId('row-prepare-shipment').click()
    const dialog = page.getByTestId('admin-shipment-dialog')
    await expect(dialog).toBeVisible()
    expect(captured.detailGets).toHaveLength(1)

    // 송장번호 없이 등록 → 필드 에러·요청 0
    await dialog.getByTestId('shipment-dialog-ok').click()
    await expect(dialog).toContainText('택배사를 선택하세요.')
    await expect(dialog).toContainText('송장번호를 입력하세요.')
    expect(captured.posts).toHaveLength(0)

    await pickOption(page, 'shipment-item', 'E2E 티셔츠 (M) · 수량 1')
    await pickOption(page, 'shipment-carrier', 'CJ대한통운')
    await dialog.getByTestId('shipment-tracking-no').locator('input').fill(' 1234567890 ')
    await expect(page.getByRole('listbox')).toHaveCount(0)
    await page.screenshot({ path: 'playwright-report/fe-27/shipment-dialog-desktop.png' })
    const listCallsBefore = captured.listQueries.length
    await dialog.getByTestId('shipment-dialog-ok').click()

    await expect(page.locator('[data-sonner-toast][data-type="info"]')).toContainText('CJ대한통운 1234567890')
    expect(captured.posts[0]!.url).toContain('/admin/orders/items/oit_E2E0000000000000000000001/prepare-shipment')
    expect(JSON.parse(captured.posts[0]!.body)).toEqual({ carrier: 'CJ', trackingNo: '1234567890' })
    await expect(dialog).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listCallsBefore)
  })

  test('⑥ 상세 approvable 클레임 "승인" → 확인 다이얼로그 → POST claims/{id}/approve → info 토스트 → 상세 재조회 / 목록·상세 스크린샷', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/orders/${PAID_ID}`)
    await page.getByTestId('claim-approve').click()
    const confirm = page.getByTestId('admin-claim-decision-dialog')
    await expect(confirm).toContainText('반품 요청 (E2E 양말)')
    await confirm.getByTestId('admin-claim-decision-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="info"]')).toContainText('반품 요청을 승인했습니다.')
    expect(captured.posts[0]!.url).toContain('/admin/claims/clm_E2E0000000000000000000001/approve')
    expect(captured.detailGets.length).toBeGreaterThanOrEqual(2)
    await expect(confirm).toBeHidden()
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 10_000 })
    // fullPage 캡처는 고정 사이드바 잔상(FE-26 트랩) → 문서 높이로 뷰포트를 맞춘 뒤 캡처
    const documentHeight = await page.evaluate(() => document.documentElement.scrollHeight)
    await page.setViewportSize({ width: 1280, height: documentHeight })
    await page.screenshot({ path: 'playwright-report/fe-27/detail-desktop.png' })
    await page.setViewportSize({ width: 1280, height: 720 })

    // 모바일: 본문 가로 스크롤 없음(표 내부 스크롤만 허용)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/admin/orders')
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    const bodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(bodyOverflow).toBe(0)
    await page.screenshot({ path: 'playwright-report/fe-27/list-mobile.png' })
  })

  test('⑦ FE-28 상세 "거절" → 사유 다이얼로그(반품이라 "이미 발송됨" 없음·사유 필수) → POST reject body{reasonCode,memo} → danger 토스트 → 재조회 / 거부 사유·메모 표기', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/orders/${PAID_ID}`)
    // 거부된 취소 클레임 행: 거부 사유·메모 표기
    await expect(page.getByTestId('claim-reject-reason').first()).toContainText('거부: 이미 발송됨 — 오전 출고분')
    // FE-29: 검수 불합격 반품 행 — 사유 라벨·회수 송장·회수 확인·검수 chip·첨부 썸네일 2(클릭 확대)·품목 배송 "재발송" chip·상세엔 액션 없음
    await expect(page.getByTestId('claim-reject-reason').nth(1)).toContainText('거부: 검수 불합격 — 사용 흔적')
    await expect(page.getByTestId('claim-return-shipment').first()).toContainText('회수 CJ대한통운 RTN-0002')
    await expect(page.getByTestId('claim-picked-up-at').first()).toContainText('회수 확인 2026.09.11 09:00')
    await expect(page.getByTestId('claim-inspection-chip').first()).toHaveText('검수 불합격')
    await expect(page.getByTestId('claim-exchange-option')).toHaveCount(1) // FE-30: 교환 클레임만 옵션 라벨
    await expect(page.getByTestId('claim-exchange-option')).toContainText('교환 색상: 빨강 → 색상: 파랑')
    await expect(page.getByTestId('claim-attachment-thumb')).toHaveCount(2)
    await expect(page.getByTestId('item-reshipment-chip')).toHaveText('재발송')
    await expect(page.getByTestId('row-inspect')).toHaveCount(0)
    await page.getByTestId('claim-attachment-thumb').first().click()
    await expect(page.getByTestId('claim-attachment-preview')).toBeVisible()
    await page.screenshot({ path: 'playwright-report/fe-29/order-detail-return-claim.png' })
    await page.getByTestId('claim-attachment-preview').getByRole('button', { name: '닫기' }).click()
    await expect(page.getByTestId('claim-attachment-preview')).toBeHidden()
    await page.getByTestId('claim-reject').click()
    const dialog = page.getByTestId('admin-claim-reject-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('반품 요청 거부')
    // 사유 미선택 → 거부 버튼 비활성·요청 0
    await expect(dialog.getByTestId('reject-dialog-ok')).toBeDisabled()
    await dialog.getByTestId('reject-reason').click()
    await expect(page.getByRole('option', { name: '이미 발송됨', exact: true })).toHaveCount(0)
    await page.getByRole('option', { name: '정책상 불가', exact: true }).click()
    await dialog.getByTestId('reject-memo').locator('textarea').first().fill(' 기간 경과 ')
    await dialog.getByTestId('reject-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toContainText('반품 요청을 거절했습니다.')
    expect(captured.posts[0]!.url).toContain('/admin/claims/clm_E2E0000000000000000000001/reject')
    expect(JSON.parse(captured.posts[0]!.body)).toEqual({ reasonCode: 'OUT_OF_POLICY', memo: '기간 경과' })
    await expect(dialog).toBeHidden()
    expect(captured.detailGets.length).toBeGreaterThanOrEqual(2)
  })

  test('⑧ FE-28 송장 등록 422 CLAIM_STATE_INVALID(취소 요청 진행 중) → warning 토스트 → 다이얼로그 닫힘·상세 재조회', async ({ page }) => {
    const captured = await mockAdminApi(page, { shipmentStatus: 422 })
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/orders/${PAID_ID}`)
    await page.getByTestId('open-shipment').click()
    const dialog = page.getByTestId('admin-shipment-dialog')
    await pickOption(page, 'shipment-item', 'E2E 티셔츠 (M) · 수량 1')
    await pickOption(page, 'shipment-carrier', 'CJ대한통운')
    await dialog.getByTestId('shipment-tracking-no').locator('input').fill('1234567890')
    await dialog.getByTestId('shipment-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toContainText('현재 상태에서 처리할 수 없는 클레임')
    await expect(dialog).toBeHidden()
    expect(captured.detailGets.length).toBeGreaterThanOrEqual(2)
  })

  test('⑨ FE-36(Track 89-A) 상세 결제 표: PG 거래번호·실패코드 컬럼 → 전액 환불·PAID 잔존 행 경고 배지(C-12) + "취소 처리" → 다이얼로그(금액·사유 필수) 노출까지만(실행 안 함) → 닫기', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/orders/${PAID_ID}`)
    await expect(page.getByTestId('payment-row')).toHaveCount(1)
    await expect(page.getByTestId('payment-pg-tid')).toHaveText('MOCK-TID-0001')
    await expect(page.getByTestId('payment-failure-code')).toHaveText('—')
    await expect(page.getByTestId('payment-cancel-lost')).toHaveText('환불 전액 완료·취소 미반영')

    const postsBefore = captured.posts.length
    await page.getByTestId('payment-cancel').click()
    const dialog = page.getByTestId('admin-payment-cancel-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByTestId('payment-cancel-amount')).toHaveText('32,900원')
    await expect(dialog.getByTestId('payment-cancel-dialog-ok')).toBeDisabled() // 사유 필수
    await dialog.getByTestId('payment-cancel-reason').locator('textarea').first().fill('콜백 유실 보정')
    await expect(dialog.getByTestId('payment-cancel-dialog-ok')).toBeEnabled()
    // 실제 취소 처리는 데이터를 바꾸므로 여기서는 노출까지만 확인하고 닫는다
    await dialog.getByTestId('payment-cancel-dialog-close').click()
    await expect(dialog).toBeHidden()
    expect(captured.posts.length).toBe(postsBefore)
  })
})
