import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 배송 관리(FE-37·Track 89-B) E2E 스모크. 로그인은 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip), 배송 API는 page.route로
 * mock해 로컬 DB를 바꾸지 않고 결정적으로 검증한다(진입 → 조회 범위 필터 → URL·API 파라미터 → 행 클릭 상세 → 송장 수정 다이얼로그 노출까지.
 * PATCH는 호출하지 않는다).
 */
const SHIPPING_ID = 'dlv_E2E0000000000000000000001'
const DELIVERED_ID = 'dlv_E2E0000000000000000000002'
const RETURN_ID = 'dlv_E2E0000000000000000000003'

const SUMMARIES = [
  { deliveryId: SHIPPING_ID, orderId: 'ord_E2E1', orderNo: 'ORD-20260916-0001', productName: 'E2E 티셔츠', recipientName: '홍길동', direction: 'OUTBOUND', status: 'SHIPPING', carrier: 'CJ', trackingNo: 'E2E-TRK-0001', shippedAt: '2026-09-16T10:00:00+09:00' },
  { deliveryId: DELIVERED_ID, orderId: 'ord_E2E2', orderNo: 'ORD-20260915-0002', productName: 'E2E 모자', recipientName: '김철수', direction: 'OUTBOUND', status: 'DELIVERED', carrier: 'HANJIN', trackingNo: 'E2E-TRK-0002', shippedAt: '2026-09-15T10:00:00+09:00', deliveredAt: '2026-09-17T10:00:00+09:00' },
  { deliveryId: RETURN_ID, orderId: 'ord_E2E2', orderNo: 'ORD-20260915-0002', productName: 'E2E 모자', recipientName: '김철수', direction: 'RETURN', status: 'DELIVERED', carrier: 'POST', trackingNo: 'E2E-RTN-0003', shippedAt: '2026-09-18T10:00:00+09:00', deliveredAt: '2026-09-19T10:00:00+09:00', claimId: 'clm_E2E3', claimType: 'RETURN' },
]

const DETAILS: Record<string, object> = {
  [SHIPPING_ID]: { ...SUMMARIES[0], orderItemId: 'oit_E2E1', optionLabel: 'M', quantity: 1, orderItemStatus: 'SHIPPING',
    shippingAddress: { recipientName: '홍길동', recipientPhone: '010-0000-0000', zonecode: '06236', addressRoad: '서울 강남구 테헤란로 1', addressDetail: '101호' } },
  [DELIVERED_ID]: { ...SUMMARIES[1], orderItemId: 'oit_E2E2', quantity: 1, orderItemStatus: 'DELIVERED',
    shippingAddress: { recipientName: '김철수', recipientPhone: '010-1111-1111', zonecode: '04524', addressRoad: '서울 중구 세종대로 1' } },
}

interface Captured { listQueries: URLSearchParams[]; patches: string[] }

async function mockAdminApi(page: Page): Promise<Captured> {
  const captured: Captured = { listQueries: [], patches: [] }
  await page.route((url) => /\/api\/v1\/admin\/deliveries\/dlv_[^/]+\/tracking$/.test(url.pathname), (route) => {
    captured.patches.push(route.request().url())
    return route.fulfill({ json: { deliveryPublicId: SHIPPING_ID, status: 'SHIPPING', carrier: 'CJ', trackingNo: 'E2E-TRK-0001' } })
  })
  await page.route((url) => /\/api\/v1\/admin\/deliveries\/dlv_[^/]+$/.test(url.pathname), (route) => {
    const id = new URL(route.request().url()).pathname.split('/').pop() ?? ''
    return route.fulfill({ json: DETAILS[id] ?? DETAILS[SHIPPING_ID] })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/admin/deliveries'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const scope = query.get('scope') ?? 'ORIGINAL'
    const filtered = scope === 'ALL' ? SUMMARIES : scope === 'RETURN' ? SUMMARIES.filter((item) => item.direction === 'RETURN') : SUMMARIES.filter((item) => item.direction === 'OUTBOUND' && !item.claimId)
    return route.fulfill({ json: { items: filtered, page: 0, size: 20, totalCount: filtered.length, hasNext: false } })
  })
  return captured
}

/** Vuetify select: 활성화 후 옵션 클릭. */
async function pickOption(page: Page, testId: string, optionName: string): Promise<void> {
  await page.getByTestId(testId).click()
  await page.getByRole('option', { name: optionName, exact: true }).click()
}

test.describe('관리자 배송 관리(FE-37)', () => {
  test('① 진입(원 발송 2행·상태 chip) → 조회 범위 RETURN 필터(URL·API scope·회수/반품 배지) → 전체 → 행 클릭 상세 → 송장 수정 다이얼로그 노출(DELIVERED는 비활성·PATCH 0)', async ({ page }) => {
    const captured = await mockAdminApi(page)
    await loginAs(page, 'ADMIN')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/admin/orders/deliveries')

    // 기본 scope ORIGINAL: 원 발송 2행·claim chip 없음·API scope=ORIGINAL
    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await expect(page.getByTestId('status-chip').first()).toHaveText('배송중')
    await expect(page.getByTestId('status-chip').first()).toHaveClass(/adm-chip--info/)
    await expect(page.getByTestId('status-chip').nth(1)).toHaveText('배송완료')
    await expect(page.getByTestId('status-chip').nth(1)).toHaveClass(/adm-chip--success/)
    await expect(page.getByTestId('claim-chip')).toHaveCount(0)
    await expect(page.getByTestId('row-tracking-no').first()).toHaveText('E2E-TRK-0001')
    await expect(page.getByTestId('row-copy-tracking').first()).toBeVisible()
    expect(captured.listQueries[0]?.get('scope')).toBe('ORIGINAL')
    expect(captured.listQueries[0]?.get('sort')).toBe('LATEST')

    // 조회 범위 → 반품·교환 회수: URL scope=RETURN·API scope=RETURN·회수 배지(warning)·반품 회수 claim chip
    await pickOption(page, 'filter-scope', '반품·교환 회수')
    await expect(page).toHaveURL(/scope=RETURN/)
    await expect(page.getByTestId('status-chip')).toHaveCount(1)
    await expect(page.getByTestId('direction-chip')).toHaveText('회수')
    await expect(page.getByTestId('direction-chip')).toHaveClass(/adm-chip--warning/)
    await expect(page.getByTestId('claim-chip')).toHaveText('반품 회수')
    expect(captured.listQueries.at(-1)?.get('scope')).toBe('RETURN')

    // 전체 → 3행 → 배송중 행 클릭 → 상세 다이얼로그(배송지·주문·송장) → 송장 수정 활성 → 수정 다이얼로그(현재 값 기본)
    await pickOption(page, 'filter-scope', '전체')
    await expect(page.getByTestId('status-chip')).toHaveCount(3)
    await page.getByTestId('row-tracking-no').first().click()
    const detailDialog = page.getByTestId('admin-delivery-detail-dialog')
    await expect(detailDialog).toBeVisible()
    await expect(detailDialog.getByTestId('detail-tracking-no')).toHaveText('E2E-TRK-0001')
    await expect(detailDialog.getByTestId('detail-carrier')).toHaveText('CJ대한통운')
    await expect(detailDialog.getByTestId('detail-shipping')).toContainText('010-0000-0000')
    await expect(detailDialog.getByTestId('detail-order-no')).toHaveText('ORD-20260916-0001')
    const correctButton = detailDialog.getByTestId('delivery-correct-tracking')
    await expect(correctButton).toBeEnabled()
    await correctButton.click()
    const trackingDialog = page.getByTestId('admin-delivery-tracking-dialog')
    await expect(trackingDialog).toBeVisible()
    await expect(trackingDialog.getByTestId('tracking-no').locator('input')).toHaveValue('E2E-TRK-0001')
    await expect(trackingDialog.getByTestId('tracking-dialog-ok')).toBeDisabled() // 사유 비어 있음
    await trackingDialog.getByTestId('tracking-dialog-cancel').click()
    await expect(trackingDialog).toBeHidden()
    await detailDialog.getByTestId('delivery-detail-close').click()
    await expect(detailDialog).toBeHidden()

    // 배송완료 행 → 송장 수정 비활성(툴팁 사유)
    await page.getByTestId('row-tracking-no').nth(1).click()
    await expect(detailDialog).toBeVisible()
    await expect(detailDialog.getByTestId('detail-tracking-no')).toHaveText('E2E-TRK-0002')
    await expect(detailDialog.getByTestId('delivery-correct-tracking')).toBeDisabled()
    await detailDialog.getByTestId('delivery-correct-wrapper').hover()
    await expect(page.getByTestId('delivery-correct-blocked')).toContainText('배송완료')
    expect(captured.patches).toHaveLength(0)
  })
})
