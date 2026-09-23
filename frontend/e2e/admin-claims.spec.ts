import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 취소·반품·교환 목록(FE-28) E2E. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 클레임·주문 API는 page.route로
 * mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(메뉴 1항목·탭→URL→API type·처리 대기 chip·필터·승인·거부 사유 필수/유형 제한·
 * 주문번호→상세→복귀·1440 가로 스크롤 0). FE-29: 반품 승인 행의 회수 확인·검수(PASS 재입고 / FAIL 사유·재발송 송장) 액션·회수/검수 표기.
 */
const ORDER_ID = 'ord_E2E0000000000000000000001'
const CANCEL_CLAIM = 'clm_E2E0000000000000000000101'
const RETURN_CLAIM = 'clm_E2E0000000000000000000102'
const DONE_CLAIM = 'clm_E2E0000000000000000000103'
const PICKUP_CLAIM = 'clm_E2E0000000000000000000104'
const INSPECT_CLAIM = 'clm_E2E0000000000000000000105'
const EXCHANGE_SHIP_CLAIM = 'clm_E2E0000000000000000000106'
const EXCHANGE_DELIVER_CLAIM = 'clm_E2E0000000000000000000107'
const EXCHANGE_DELIVERY_ID = 'dlv_E2E7'
const RETURN_SHIPMENT = { deliveryPublicId: 'dlv_E2E4', direction: 'RETURN', carrier: 'CJ', trackingNo: 'RTN-0004', status: 'SHIPPING', shippedAt: '2026-09-16T12:00:00+09:00', deliveredAt: null }

const CLAIMS = [
  {
    claimId: CANCEL_CLAIM, type: 'CANCEL', status: 'REQUESTED', requestedAt: '2026-09-16T11:00:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000001', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 티셔츠', optionLabel: 'M', quantity: 1, amount: 19900, reasonCode: 'BUYER_CHANGED_MIND', reasonDetail: '색상 변경',
    availableActions: ['APPROVE', 'REJECT'],
  },
  {
    claimId: RETURN_CLAIM, type: 'RETURN', status: 'REQUESTED', requestedAt: '2026-09-16T10:00:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000002', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 양말', quantity: 2, amount: 10000, reasonCode: 'PRODUCT_DEFECT',
    availableActions: ['APPROVE', 'REJECT'],
  },
  {
    claimId: DONE_CLAIM, type: 'CANCEL', status: 'COMPLETED', requestedAt: '2026-09-15T09:00:00+09:00', processedAt: '2026-09-15T09:30:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000001', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 티셔츠', optionLabel: 'M', quantity: 1, amount: 19900, reasonCode: 'DUPLICATE_ORDER', refundStatus: 'COMPLETED',
    availableActions: [],
  },
  // FE-29: 반품 승인 + 회수 송장 등록(회수 확인 대기·첨부 2)
  {
    claimId: PICKUP_CLAIM, type: 'RETURN', status: 'APPROVED', requestedAt: '2026-09-14T10:00:00+09:00', processedAt: '2026-09-14T11:00:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000002', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 회수대기 양말', quantity: 1, amount: 5000, reasonCode: 'PRODUCT_DEFECT',
    availableActions: ['CONFIRM_PICKUP'], returnShipment: RETURN_SHIPMENT, attachmentCount: 2,
  },
  // FE-29: 반품 승인 + 회수 확인(검수 대기)
  {
    claimId: INSPECT_CLAIM, type: 'RETURN', status: 'APPROVED', requestedAt: '2026-09-13T10:00:00+09:00', processedAt: '2026-09-13T11:00:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000002', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 검수대기 양말', quantity: 1, amount: 5000, reasonCode: 'WRONG_PRODUCT',
    availableActions: ['INSPECT'], returnShipment: { ...RETURN_SHIPMENT, status: 'DELIVERED', deliveredAt: '2026-09-15T09:00:00+09:00' },
    pickedUpAt: '2026-09-15T09:00:00+09:00', attachmentCount: 0,
  },
]

const ORDER_DETAIL = {
  orderId: ORDER_ID, orderNo: 'ORD-20260916-0001', orderedAt: '2026-09-16T10:00:00', paidAt: '2026-09-16T10:05:00', status: 'PAID',
  buyer: { userId: 'usr_E2E1', name: 'E2E구매자', email: 'buyer@e2e.invalid' },
  totalPrice: 29900, discountAmount: 0, shippingFee: 3000, paymentAmount: 32900, payments: [],
  items: [{ orderItemId: 'oit_E2E0000000000000000000001', productName: 'E2E 티셔츠', optionLabel: 'M', quantity: 1, unitPrice: 19900, totalPrice: 19900, status: 'CANCEL_REQUESTED', sellerName: 'E2E셀러', claims: [] }],
  cancelReasons: [], actions: [],
}

interface Captured { listQueries: URLSearchParams[]; posts: { url: string; body: string }[] }

async function mockClaimsApi(page: Page): Promise<Captured> {
  const captured: Captured = { listQueries: [], posts: [] }
  await page.route((url) => /\/api\/v1\/admin\/claims\/clm_[^/]+\/(approve|reject)$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    return route.fulfill({ json: { publicId: CANCEL_CLAIM, orderItemPublicId: 'oit_E2E0000000000000000000001', claimType: 'CANCEL', status: 'COMPLETED', reasonCode: 'BUYER_CHANGED_MIND', requestedAt: '2026-09-16T11:00:00+09:00', processedAt: '2026-09-16T12:00:00+09:00', refundStatus: 'COMPLETED' } })
  })
  await page.route((url) => /\/api\/v1\/admin\/claims\/clm_[^/]+\/(confirm-pickup|inspect)$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    return route.fulfill({ json: { publicId: PICKUP_CLAIM, orderItemPublicId: 'oit_E2E0000000000000000000002', claimType: 'RETURN', status: 'APPROVED', reasonCode: 'PRODUCT_DEFECT', requestedAt: '2026-09-14T10:00:00+09:00', processedAt: '2026-09-14T11:00:00+09:00', returnShipmentRequired: false, attachmentUrls: [] } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/claims'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const type = query.get('type')
    const status = query.get('status')
    const keyword = query.get('keyword')
    const action = query.get('action')
    let items = type === 'EXCHANGE' ? EXCHANGE_CLAIMS : CLAIMS
    if (type) items = items.filter((item) => item.type === type)
    if (status) items = items.filter((item) => item.status === status)
    if (keyword) items = items.filter((item) => item.productName.includes(keyword) || item.orderNo === keyword)
    // Track 96-4: action은 BE처럼 availableActions 보유 행만(FOLLOWUP = APPROVE·REJECT 외 전부)
    if (action) items = items.filter((item) => item.availableActions.some((each) => action === 'FOLLOWUP' ? each !== 'APPROVE' && each !== 'REJECT' : each === action))
    // pendingCount는 BE처럼 type만 반영
    const pendingCount = CLAIMS.filter((item) => item.status === 'REQUESTED' && (!type || item.type === type)).length
    return route.fulfill({ json: { items, page: 0, size: 20, totalCount: items.length, hasNext: false, pendingCount } })
  })
  await page.route((url) => /\/api\/v1\/admin\/orders\/ord_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: ORDER_DETAIL }))
  // FE-30: 교환품 발송 등록·배송완료
  await page.route((url) => /\/api\/v1\/admin\/claims\/clm_[^/]+\/register-exchange-shipment$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    return route.fulfill({ json: { deliveryPublicId: 'dlv_E2E6', status: 'SHIPPING', carrier: 'CJ', trackingNo: 'EXC-0006' } })
  })
  await page.route((url) => /\/api\/v1\/admin\/deliveries\/dlv_[^/]+\/mark-delivered$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '' })
    return route.fulfill({ json: { deliveryPublicId: EXCHANGE_DELIVERY_ID, status: 'DELIVERED', carrier: 'HANJIN', trackingNo: 'EXC-0007' } })
  })
  return captured
}

// FE-30 교환 행(교환 탭에서만 합류·전체 탭 행 수 단언 보존): 검수 합격 → 발송 대기 / 발송 중 → 배송완료 대기
const EXCHANGE_CLAIMS = [
  {
    claimId: EXCHANGE_SHIP_CLAIM, type: 'EXCHANGE', status: 'APPROVED', requestedAt: '2026-09-12T10:00:00+09:00', processedAt: '2026-09-12T11:00:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000002', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 교환대기 양말', optionLabel: '색상: 빨강', quantity: 1, amount: 5000, reasonCode: 'PRODUCT_DEFECT',
    availableActions: ['REGISTER_EXCHANGE_SHIPMENT'], returnShipment: { ...RETURN_SHIPMENT, status: 'DELIVERED', deliveredAt: '2026-09-14T09:00:00+09:00' },
    pickedUpAt: '2026-09-14T09:00:00+09:00', inspectionResult: 'PASS', restock: true, attachmentCount: 0,
    originalOptionLabel: '색상: 빨강', exchangeOptionLabel: '색상: 파랑',
  },
  {
    claimId: EXCHANGE_DELIVER_CLAIM, type: 'EXCHANGE', status: 'APPROVED', requestedAt: '2026-09-11T10:00:00+09:00', processedAt: '2026-09-11T11:00:00+09:00',
    orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000002', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
    productName: 'E2E 교환배송중 양말', optionLabel: '색상: 빨강', quantity: 1, amount: 5000, reasonCode: 'WRONG_PRODUCT',
    availableActions: ['MARK_EXCHANGE_DELIVERED'], returnShipment: { ...RETURN_SHIPMENT, status: 'DELIVERED', deliveredAt: '2026-09-13T09:00:00+09:00' },
    reshipment: { deliveryPublicId: EXCHANGE_DELIVERY_ID, direction: 'OUTBOUND', carrier: 'HANJIN', trackingNo: 'EXC-0007', status: 'SHIPPING', shippedAt: '2026-09-14T10:00:00+09:00', deliveredAt: null },
    pickedUpAt: '2026-09-13T09:00:00+09:00', inspectionResult: 'PASS', restock: false, attachmentCount: 0,
    originalOptionLabel: '색상: 빨강', exchangeOptionLabel: '색상: 파랑',
  },
]

/** Vuetify select: 활성화 후 옵션 클릭(admin-orders.spec 동일). */
async function pickOption(page: Page, testId: string, optionName: string): Promise<void> {
  await page.getByTestId(testId).click()
  await page.getByRole('option', { name: optionName, exact: true }).click()
}

test.describe('관리자 취소·반품·교환 목록(FE-28)', () => {
  test('① 사이드바 메뉴 1항목 → 목록 렌더(행 3·유형/상태/환불 chip·처리 대기 2건) → 1440px 가로 스크롤 없음 → 탭 취소 → ?type=CANCEL·API type·대기 1건·새로고침 유지 → 필요 액션 필터(Track 96-4) / 스크린샷', async ({ page }) => {
    const captured = await mockClaimsApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin')
    await page.setViewportSize({ width: 1440, height: 900 })
    // 메뉴: "취소·반품·교환" 1항목·구 3항목 없음
    await page.getByTestId('admin-sidebar').getByText('주문 관리').click()
    const sidebar = page.getByTestId('admin-sidebar')
    await expect(sidebar.getByRole('link', { name: '취소·반품·교환' })).toHaveCount(1)
    await expect(sidebar.getByRole('link', { name: '반품', exact: true })).toHaveCount(0)
    await sidebar.getByRole('link', { name: '취소·반품·교환' }).click()
    await page.waitForURL(/\/admin\/orders\/claims$/)
    await expect(page.getByTestId('admin-topbar')).toContainText('취소·반품·교환')

    await expect(page.getByTestId('row-status-chip')).toHaveCount(5)
    await expect(page.getByTestId('row-refund-chip')).toHaveCount(1)
    await expect(page.getByTestId('row-refund-chip')).toHaveText('환불 완료')
    await expect(page.getByTestId('claim-pending-chip')).toHaveText('처리 대기 2건')
    await expect(page.getByTestId('row-approve')).toHaveCount(2)
    // FE-29: 회수/검수 caption(회수 송장 + 첨부 수 / 회수 확인 일시)·액션 버튼
    await expect(page.getByTestId('row-return-caption').nth(0)).toHaveText('회수 CJ대한통운 RTN-0004 · 첨부 2')
    await expect(page.getByTestId('row-return-caption').nth(1)).toContainText('회수 확인 09.15 09:00')
    await expect(page.getByTestId('row-confirm-pickup')).toHaveCount(1)
    await expect(page.getByTestId('row-inspect')).toHaveCount(2) // C-10: 회수 확인 전 행에도 검수 진입(outlined)
    await expect(page.getByTestId('row-elapsed')).toHaveCount(4) // C-15: 진행 중(REQUESTED 2·APPROVED 2) 행만 경과 N일·COMPLETED 제외
    const overflow = await page.evaluate(() => {
      const wrapper = document.querySelector('[data-testid="admin-claim-table"] .v-table__wrapper') as HTMLElement
      return { table: wrapper.scrollWidth - wrapper.clientWidth, body: document.documentElement.scrollWidth - document.documentElement.clientWidth }
    })
    expect(overflow).toEqual({ table: 0, body: 0 })
    await page.screenshot({ path: 'playwright-report/fe-29/claims-list-desktop.png' })

    // 탭 취소 → URL·API type·대기 건수 탭 기준
    await page.getByTestId('claim-tab-CANCEL').click()
    await page.waitForURL(/type=CANCEL/)
    await expect(page.getByTestId('row-status-chip')).toHaveCount(2)
    await expect(page.getByTestId('row-return-caption')).toHaveCount(0)
    await expect(page.getByTestId('claim-pending-chip')).toHaveText('취소 처리 대기 1건')
    expect(captured.listQueries.at(-1)!.get('type')).toBe('CANCEL')
    await page.reload()
    await expect(page.getByTestId('row-status-chip')).toHaveCount(2)
    await expect(page.getByTestId('claim-tab-CANCEL')).toHaveAttribute('aria-selected', 'true')
    await page.screenshot({ path: 'playwright-report/fe-28/claims-list-cancel-tab.png' })

    // 상태 필터·검색 → API 파라미터·page 초기화
    await page.getByTestId('filter-status').click()
    await page.getByRole('option', { name: '완료', exact: true }).click()
    await expect.poll(() => captured.listQueries.at(-1)!.get('status')).toBe('COMPLETED')
    await expect(page.getByTestId('row-status-chip')).toHaveCount(1)
    await page.getByTestId('filter-keyword').locator('input').fill('양말')
    await page.getByTestId('filter-search').click()
    await expect.poll(() => captured.listQueries.at(-1)!.get('keyword')).toBe('양말')
    expect(captured.listQueries.at(-1)!.get('type')).toBe('CANCEL')
    await expect(page.getByTestId('admin-claim-empty')).toContainText('조건에 맞는 클레임이 없습니다')
    // 초기화는 탭 유지
    await page.getByTestId('filter-reset').click()
    await expect.poll(() => captured.listQueries.at(-1)!.get('status')).toBeNull()
    expect(page.url()).toContain('type=CANCEL')

    // Track 96-4 FE-56: 필요 액션 필터 → URL·API action(라벨은 행 버튼 문구와 동일) → 전체 탭에서 회수 확인 1행 → 새로고침 유지 → 초기화 해제
    await page.getByTestId('claim-tab-ALL').click()
    await page.waitForURL((url) => !url.searchParams.has('type'))
    await pickOption(page, 'filter-action', '회수 확인')
    await expect.poll(() => captured.listQueries.at(-1)!.get('action')).toBe('CONFIRM_PICKUP')
    await expect(page).toHaveURL(/action=CONFIRM_PICKUP/)
    await expect(page.getByTestId('row-status-chip')).toHaveCount(1)
    await expect(page.getByTestId('row-confirm-pickup')).toHaveCount(1)
    await page.reload()
    await expect(page.getByTestId('filter-action')).toContainText('회수 확인')
    await expect(page.getByTestId('row-status-chip')).toHaveCount(1)
    await pickOption(page, 'filter-action', '후속 처리 전체')
    await expect.poll(() => captured.listQueries.at(-1)!.get('action')).toBe('FOLLOWUP')
    await expect(page.getByTestId('row-status-chip')).toHaveCount(2) // 회수 확인 1 + 검수 1
    await page.getByTestId('filter-reset').click()
    await expect.poll(() => captured.listQueries.at(-1)!.get('action')).toBeNull()
    expect(page.url()).not.toContain('action=')
  })

  test('② 승인(확인 다이얼로그 → POST approve → info 토스트 → 재조회) / 거부: 취소 탭은 "이미 발송됨" 있음·반품은 없음·사유 필수 → POST reject body → danger 토스트 / 스크린샷', async ({ page }) => {
    const captured = await mockClaimsApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/claims')
    await expect(page.getByTestId('row-approve')).toHaveCount(2)
    const listCallsBefore = captured.listQueries.length

    // 승인(첫 행 = 최신 요청 취소 클레임)
    await page.getByTestId('row-approve').first().click()
    const confirm = page.getByTestId('admin-claim-approve-dialog')
    await expect(confirm).toContainText('취소 요청 (E2E 티셔츠)')
    await expect(confirm).toContainText('승인 즉시 환불이 진행됩니다.')
    await confirm.getByTestId('admin-claim-approve-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="info"]')).toContainText('취소 요청을 승인했습니다.')
    expect(captured.posts[0]!.url).toContain(`/admin/claims/${CANCEL_CLAIM}/approve`)
    await expect(confirm).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listCallsBefore)

    // 거부: 반품 행(둘째) → "이미 발송됨" 없음
    await page.getByTestId('row-reject').nth(1).click()
    const dialog = page.getByTestId('admin-claim-reject-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('반품 요청 거부')
    await expect(dialog.getByTestId('reject-dialog-ok')).toBeDisabled()
    await dialog.getByTestId('reject-reason').click()
    await expect(page.getByRole('option', { name: '이미 발송됨', exact: true })).toHaveCount(0)
    await expect(page.getByRole('option', { name: '정책상 불가', exact: true })).toHaveCount(1)
    await page.keyboard.press('Escape')
    await dialog.getByTestId('reject-dialog-close').click()
    await expect(dialog).toBeHidden()

    // 거부: 취소 행(첫째) → "이미 발송됨" 선택·메모 → body
    await page.getByTestId('row-reject').first().click()
    await expect(dialog).toContainText('취소 요청 거부')
    await dialog.getByTestId('reject-reason').click()
    await page.getByRole('option', { name: '이미 발송됨', exact: true }).click()
    await dialog.getByTestId('reject-memo').locator('textarea').first().fill('오전 출고분')
    await page.screenshot({ path: 'playwright-report/fe-28/claims-reject-dialog.png' })
    const postsBefore = captured.posts.length
    await dialog.getByTestId('reject-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toContainText('취소 요청을 거부했습니다.')
    expect(captured.posts[postsBefore]!.url).toContain(`/admin/claims/${CANCEL_CLAIM}/reject`)
    expect(JSON.parse(captured.posts[postsBefore]!.body)).toEqual({ reasonCode: 'ALREADY_SHIPPED', memo: '오전 출고분' })
    await expect(dialog).toBeHidden()
  })

  test('③ 주문번호 클릭 → 주문 상세(?back=클레임 목록 URL) → "목록으로" 복귀 시 탭·필터 유지', async ({ page }) => {
    await mockClaimsApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/claims?type=CANCEL&status=REQUESTED')
    await expect(page.getByTestId('row-order-no')).toHaveCount(1)
    await page.getByTestId('row-order-no').first().click()
    await page.waitForURL(/\/admin\/orders\/ord_.*back=/)
    expect(decodeURIComponent(page.url())).toContain('back=/admin/orders/claims?type=CANCEL&status=REQUESTED')
    await expect(page.getByTestId('admin-topbar')).toContainText('전체 주문')
    await page.getByTestId('back-to-list').click()
    await page.waitForURL(/\/admin\/orders\/claims\?type=CANCEL&status=REQUESTED$/)
    await expect(page.getByTestId('claim-tab-CANCEL')).toHaveAttribute('aria-selected', 'true')
  })

  test('④ FE-29 반품: 회수 확인(확인 다이얼로그 → POST confirm-pickup → info 토스트 → 재조회) / 검수 PASS 재입고 필수 → body{result,restock} / 검수 FAIL 사유 검수 불합격 고정·재발송 송장 필수 → body / 스크린샷', async ({ page }) => {
    const captured = await mockClaimsApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/claims?type=RETURN')
    await expect(page.getByTestId('row-confirm-pickup')).toHaveCount(1)
    const listCallsBefore = captured.listQueries.length

    // C-10(Track 96-1): 회수 확인 전 행의 검수 진입 → 체크 전 확인 비활성 → 체크 + PASS 재입고 → confirm-pickup → inspect 순차 POST
    await page.getByTestId('row-inspect').first().click()
    const combined = page.getByTestId('admin-claim-inspect-dialog')
    await expect(combined.getByTestId('inspect-pickup-check')).toBeVisible()
    await combined.getByTestId('inspect-result-PASS').click()
    await combined.getByTestId('inspect-restock-true').click()
    await expect(combined.getByTestId('inspect-dialog-ok')).toBeDisabled()
    await combined.getByTestId('inspect-pickup-check').click()
    await expect(combined.getByTestId('inspect-dialog-ok')).toBeEnabled()
    const combinedPostsBefore = captured.posts.length
    await combined.getByTestId('inspect-dialog-ok').click()
    await expect(combined).toBeHidden()
    expect(captured.posts.slice(combinedPostsBefore).map((post) => post.url.split('/admin/claims/')[1])).toEqual([`${PICKUP_CLAIM}/confirm-pickup`, `${PICKUP_CLAIM}/inspect`])
    // 합격 토스트가 사라진 뒤 진행(아래 단독 검수 PASS 토스트 단언이 strict mode로 2개를 잡지 않도록)
    const combinedToast = page.locator('[data-sonner-toast][data-type="info"]').filter({ hasText: '검수 합격' })
    await expect(combinedToast).toBeVisible()
    await expect(combinedToast).toBeHidden({ timeout: 10_000 })

    // 회수 확인
    await page.getByTestId('row-confirm-pickup').click()
    const pickup = page.getByTestId('admin-claim-pickup-dialog')
    await expect(pickup).toContainText('반품 요청 (E2E 회수대기 양말)')
    await expect(pickup).toContainText('검수 합격 시')
    await pickup.getByTestId('admin-claim-pickup-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="info"]').filter({ hasText: '회수를 확인했습니다' })).toBeVisible()
    expect(captured.posts.at(-1)!.url).toContain(`/admin/claims/${PICKUP_CLAIM}/confirm-pickup`)
    await expect(pickup).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listCallsBefore)

    // 검수 PASS: 결과 선택 전 확인 비활성 → PASS → 재입고 미선택 오류 → 재입고 → body(회수 확인된 행 = 두 번째 검수 버튼)
    await page.getByTestId('row-inspect').last().click()
    const dialog = page.getByTestId('admin-claim-inspect-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('E2E 검수대기 양말')
    await expect(dialog.getByTestId('inspect-dialog-ok')).toBeDisabled()
    await dialog.getByTestId('inspect-result-PASS').click()
    await expect(dialog.getByTestId('inspect-restock')).toBeVisible()
    await dialog.getByTestId('inspect-dialog-ok').click()
    await expect(dialog.getByTestId('inspect-restock')).toContainText('재입고 여부를 선택하세요.')
    await dialog.getByTestId('inspect-restock-true').click()
    await page.screenshot({ path: 'playwright-report/fe-29/claims-inspect-dialog.png' })
    let postsBefore = captured.posts.length
    await dialog.getByTestId('inspect-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="info"]').filter({ hasText: '검수 합격' })).toBeVisible()
    expect(captured.posts[postsBefore]!.url).toContain(`/admin/claims/${INSPECT_CLAIM}/inspect`)
    expect(JSON.parse(captured.posts[postsBefore]!.body)).toEqual({ result: 'PASS', restock: true })
    await expect(dialog).toBeHidden()

    // 검수 FAIL: 사유 "검수 불합격" 고정 표기(select 없음·D-172)·재발송 택배사/송장 필수 → body
    await page.getByTestId('row-inspect').last().click()
    await expect(dialog).toBeVisible()
    await dialog.getByTestId('inspect-result-FAIL').click()
    await expect(dialog.getByTestId('inspect-reason')).toContainText('불합격 사유: 검수 불합격')
    await expect(dialog.locator('.v-select')).toHaveCount(1) // 재발송 택배사만
    await dialog.getByTestId('inspect-dialog-ok').click()
    await expect(dialog.getByTestId('inspect-reship-carrier')).toContainText('재발송 택배사를 선택하세요.')
    await expect(dialog.getByTestId('inspect-reship-tracking-no')).toContainText('재발송 송장번호를 입력하세요.')
    await dialog.getByTestId('inspect-memo').locator('textarea').first().fill('사용 흔적')
    await dialog.getByTestId('inspect-reship-carrier').click()
    await page.getByRole('option', { name: '한진택배', exact: true }).click()
    await dialog.getByTestId('inspect-reship-tracking-no').locator('input').fill('RESHIP-0001')
    postsBefore = captured.posts.length
    await dialog.getByTestId('inspect-dialog-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="error"]').filter({ hasText: '검수 불합격' })).toBeVisible()
    expect(JSON.parse(captured.posts[postsBefore]!.body)).toEqual({
      result: 'FAIL', rejectReasonCode: 'INSPECTION_FAILED', memo: '사용 흔적', reshipCarrier: 'HANJIN', reshipTrackingNo: 'RESHIP-0001',
    })
    await expect(dialog).toBeHidden()
  })

  test('⑤ FE-30 교환: 교환 탭 → 옵션 라벨(빨강 → 파랑)·"교환품 발송"·"배송완료" 버튼 → 발송 다이얼로그(택배사·송장 필수) → POST register-exchange-shipment body → info 토스트 / 배송완료 확인 → POST mark-delivered(reshipment.deliveryPublicId) → info 토스트 / 스크린샷', async ({ page }) => {
    const captured = await mockClaimsApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/claims?type=EXCHANGE')
    await expect(page.getByTestId('row-status-chip')).toHaveCount(2)
    await expect(page.getByTestId('row-exchange-option')).toHaveCount(2)
    await expect(page.getByTestId('row-exchange-option').first()).toContainText('색상: 빨강 → 색상: 파랑')
    await expect(page.getByTestId('row-register-exchange-shipment')).toHaveCount(1)
    await expect(page.getByTestId('row-mark-exchange-delivered')).toHaveCount(1)
    await page.screenshot({ path: 'playwright-report/fe-30/admin-claims-exchange.png', fullPage: true })

    // 교환품 발송: 빈 제출 불가 → 택배사·송장 → POST body
    await page.getByTestId('row-register-exchange-shipment').click()
    const dialog = page.getByTestId('admin-exchange-shipment-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByTestId('exchange-shipment-option')).toHaveText('색상: 파랑')
    await expect(dialog.getByTestId('exchange-shipment-ok')).toBeDisabled()
    await dialog.getByTestId('exchange-shipment-carrier').click()
    await page.getByRole('option', { name: 'CJ대한통운' }).click()
    await dialog.getByTestId('exchange-shipment-tracking-no').locator('input').fill('EXC-0006')
    const postsBefore = captured.posts.length
    await dialog.getByTestId('exchange-shipment-ok').click()
    await expect(page.locator('[data-sonner-toast][data-type="info"]').filter({ hasText: '교환품 발송을 등록했습니다' })).toBeVisible()
    expect(captured.posts[postsBefore]!.url).toContain(`/admin/claims/${EXCHANGE_SHIP_CLAIM}/register-exchange-shipment`)
    expect(JSON.parse(captured.posts[postsBefore]!.body)).toEqual({ carrier: 'CJ', trackingNo: 'EXC-0006' })
    await expect(dialog).toBeHidden()

    // 배송완료: 확인 다이얼로그 → 기존 mark-delivered(reshipment.deliveryPublicId)
    await page.getByTestId('row-mark-exchange-delivered').click()
    const confirm = page.getByTestId('exchange-delivered-dialog')
    await expect(confirm).toBeVisible()
    await expect(confirm).toContainText('교환 옵션으로 바뀌고')
    const deliveredBefore = captured.posts.length
    await confirm.getByRole('button', { name: '배송완료' }).click()
    await expect(page.locator('[data-sonner-toast][data-type="info"]').filter({ hasText: '교환품 배송완료 처리' })).toBeVisible()
    expect(captured.posts[deliveredBefore]!.url).toContain(`/admin/deliveries/${EXCHANGE_DELIVERY_ID}/mark-delivered`)
  })

  test('⑥ FE-36(Track 89-A) 환불 축: 환불 상태 필터 → URL·API refundStatus / INITIATE_REFUND 행 "환불 개시" → 다이얼로그(품목 금액·금액 입력·개시 버튼) 노출까지만(실행 안 함) → 닫기', async ({ page }) => {
    const captured = await mockClaimsApi(page)
    // 자동 환불이 FAILED로 끝난 승인 취소 1건(BE availableActions INITIATE_REFUND)을 목록에 얹는다. 나중 등록 route가 우선한다.
    const LOST_CLAIM = 'clm_E2E0000000000000000000108'
    const lostRow = {
      claimId: LOST_CLAIM, type: 'CANCEL', status: 'APPROVED', requestedAt: '2026-09-12T10:00:00+09:00', processedAt: '2026-09-12T10:30:00+09:00',
      orderId: ORDER_ID, orderItemId: 'oit_E2E0000000000000000000001', orderNo: 'ORD-20260916-0001', buyerName: 'E2E구매자', buyerEmail: 'buyer@e2e.invalid',
      productName: 'E2E 티셔츠', optionLabel: 'M', quantity: 1, amount: 19900, reasonCode: 'BUYER_CHANGED_MIND', refundStatus: 'FAILED',
      availableActions: ['INITIATE_REFUND'],
    }
    await page.route((url) => url.pathname.endsWith('/api/v1/admin/claims'), (route) => {
      const query = new URL(route.request().url()).searchParams
      captured.listQueries.push(query)
      const refundStatus = query.get('refundStatus')
      const all = [lostRow, ...CLAIMS]
      const items = refundStatus ? all.filter((item) => 'refundStatus' in item && item.refundStatus === refundStatus) : all
      return route.fulfill({ json: { items, page: 0, size: 20, totalCount: items.length, hasNext: false, pendingCount: 2 } })
    })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/orders/claims')
    await expect(page.getByTestId('row-initiate-refund')).toHaveCount(1)

    await pickOption(page, 'filter-refund-status', '환불 실패')
    await expect(page).toHaveURL(/refundStatus=FAILED/)
    // toHaveURL은 내비게이션만 보장하고 API 도착은 보장하지 않는다 → 목록 호출이 기록될 때까지 poll
    await expect.poll(() => captured.listQueries.at(-1)!.get('refundStatus')).toBe('FAILED')
    await expect(page.getByTestId('row-initiate-refund')).toHaveCount(1)

    const postsBefore = captured.posts.length
    await page.getByTestId('row-initiate-refund').click()
    const dialog = page.getByTestId('admin-refund-initiate-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('취소 환불 개시')
    await expect(dialog.getByTestId('refund-initiate-item-amount')).toHaveText('19,900원')
    await expect(dialog.getByTestId('refund-initiate-amount').locator('input')).toHaveValue('19900')
    await expect(dialog.getByTestId('refund-initiate-dialog-ok')).toBeEnabled()
    // 실제 환불 개시는 데이터를 바꾸므로 여기서는 노출까지만 확인하고 닫는다
    await dialog.getByTestId('refund-initiate-dialog-close').click()
    await expect(dialog).toBeHidden()
    expect(captured.posts.length).toBe(postsBefore)
  })
})
