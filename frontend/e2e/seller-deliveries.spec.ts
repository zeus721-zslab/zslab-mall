import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { mockSellerMe, pagedResponse, pickOption } from './helpers/seller-mock'

/**
 * 셀러 배송 화면(Track 90-B-3·D-191) E2E. 로그인은 loginAs(SELLER), 배송 목록·배송완료(POST mark-delivered)·송장 정정(PATCH tracking)은 page.route mock.
 * ① 진입점 안내 → 행·상태 chip → scope 필터(URL·API) → 행 메뉴(SHIPPING만) → 배송완료 성공 ② 송장 정정 형식: 클라이언트 검증 문구(PATCH 없음) →
 * 서버 400 fieldErrors 문구 필드 오류 유지 → 재시도 성공(D-227·중복 409 제거).
 */
const SHIPPING_ID = 'dlv_E2E0000000000000000000001'
const DELIVERED_ID = 'dlv_E2E0000000000000000000002'
const RETURN_ID = 'dlv_E2E0000000000000000000003'

const SUMMARIES = [
  { deliveryId: SHIPPING_ID, orderItemId: 'oit_E2E1', orderNo: '20260916-E2E1', productName: 'E2E 주전자', optionLabel: '블랙', quantity: 1, recipientName: '박지훈', direction: 'OUTBOUND', status: 'SHIPPING', carrier: 'HANJIN', trackingNo: 'E2E-TRK-0001', shippedAt: '2026-09-16T12:00:00+09:00' },
  { deliveryId: DELIVERED_ID, orderItemId: 'oit_E2E2', orderNo: '20260914-E2E2', productName: 'E2E 달력', quantity: 1, recipientName: '조수아', direction: 'OUTBOUND', status: 'DELIVERED', carrier: 'CJ', trackingNo: 'E2E-TRK-0002', shippedAt: '2026-09-15T16:30:00+09:00', deliveredAt: '2026-09-16T21:30:00+09:00' },
  { deliveryId: RETURN_ID, orderItemId: 'oit_E2E2', orderNo: '20260914-E2E2', productName: 'E2E 달력', quantity: 1, recipientName: '조수아', direction: 'RETURN', status: 'DELIVERED', carrier: 'POST', trackingNo: 'E2E-RTN-0003', shippedAt: '2026-09-18T10:00:00+09:00', deliveredAt: '2026-09-19T10:00:00+09:00', claimId: 'clm_E2E3', claimType: 'RETURN' },
]

interface Captured { listQueries: URLSearchParams[]; delivered: string[]; patches: { url: string; body: unknown }[] }

const TRACKING_NO_FORMAT_MESSAGE = '송장번호는 숫자·영문·하이픈 8~20자로 입력해 주세요.'

async function mockSellerDeliveries(page: Page, options: { rejectFirstPatch?: boolean } = {}): Promise<Captured> {
  const captured: Captured = { listQueries: [], delivered: [], patches: [] }
  await mockSellerMe(page)
  await page.route((url) => /\/api\/v1\/deliveries\/dlv_[^/]+\/mark-delivered$/.test(url.pathname), (route) => {
    captured.delivered.push(route.request().url())
    return route.fulfill({ json: { deliveryPublicId: SHIPPING_ID, status: 'DELIVERED', carrier: 'HANJIN', trackingNo: 'E2E-TRK-0001' } })
  })
  await page.route((url) => /\/api\/v1\/seller\/deliveries\/dlv_[^/]+\/tracking$/.test(url.pathname), (route) => {
    captured.patches.push({ url: route.request().url(), body: route.request().postDataJSON() })
    if (options.rejectFirstPatch && captured.patches.length === 1) {
      return route.fulfill({ status: 400, json: {
        type: 'about:blank', title: 'Validation Failed', status: 400, code: 'VALIDATION_FAILED', detail: `trackingNo: ${TRACKING_NO_FORMAT_MESSAGE}`,
        fieldErrors: [{ field: 'trackingNo', message: TRACKING_NO_FORMAT_MESSAGE }],
      } })
    }
    return route.fulfill({ json: { deliveryPublicId: SHIPPING_ID, status: 'SHIPPING', carrier: 'CJ', trackingNo: 'E2E-TRK-FIX' } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/deliveries'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const scope = query.get('scope') ?? 'ORIGINAL'
    const filtered = scope === 'ALL' ? SUMMARIES : scope === 'RETURN' ? SUMMARIES.filter((item) => item.direction === 'RETURN') : SUMMARIES.filter((item) => item.direction === 'OUTBOUND' && !item.claimId)
    return route.fulfill({ json: pagedResponse(filtered) })
  })
  return captured
}

test.describe('셀러 배송 화면(90-B-3)', () => {
  test('① 진입점 안내(발송은 주문 화면 링크) → 원 발송 2행·상태 chip·택배사 캡션 → scope RETURN(URL·API·회수 배지) → 행 메뉴는 SHIPPING만 → 배송완료 POST 성공 → 토스트·재조회', async ({ page }) => {
    const captured = await mockSellerDeliveries(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/deliveries')

    await expect(page.getByTestId('seller-delivery-entry-notice')).toContainText('발송하지 않은 결제완료 품목은')
    await expect(page.getByTestId('seller-delivery-shipping-ready-link')).toHaveAttribute('href', '/seller/orders?status=PAID')

    await expect(page.getByTestId('status-chip')).toHaveCount(2)
    await expect(page.getByTestId('status-chip').first()).toHaveText('배송중')
    await expect(page.getByTestId('status-chip').nth(1)).toHaveText('배송완료')
    await expect(page.getByTestId('status-chip').nth(1)).toHaveClass(/slr-chip--success/)
    await expect(page.getByTestId('row-carrier').first()).toHaveText('한진택배')
    await expect(page.getByTestId('row-shipped-at').first()).toHaveText('2026.09.16 12:00')
    await expect(page.getByTestId('claim-chip')).toHaveCount(0)
    // 행 메뉴: SHIPPING 행만(DELIVERED는 —)
    await expect(page.getByTestId('row-menu')).toHaveCount(1)
    expect(captured.listQueries[0]?.get('scope')).toBe('ORIGINAL')
    expect(captured.listQueries[0]?.get('sort')).toBe('LATEST')

    // scope RETURN → URL·API·회수 배지
    await pickOption(page, 'filter-scope', '반품·교환 회수')
    await expect(page).toHaveURL(/scope=RETURN/)
    await expect(page.getByTestId('direction-chip')).toHaveCount(1)
    await expect(page.getByTestId('claim-chip')).toHaveText('반품 회수')
    await expect.poll(() => captured.listQueries.at(-1)?.get('scope')).toBe('RETURN')
    await expect(page.getByTestId('row-menu')).toHaveCount(0) // 회수·DELIVERED → 액션 없음

    // 초기화 → 원 발송 → 배송완료
    await page.getByTestId('filter-reset').click()
    await expect(page).toHaveURL(/\/seller\/deliveries$/)
    await expect(page.getByTestId('row-menu')).toHaveCount(1)
    await page.getByTestId('row-menu').click()
    await page.getByTestId('row-mark-delivered').click()
    const dialog = page.getByTestId('seller-mark-delivered-dialog')
    await expect(dialog.getByTestId('delivered-target')).toContainText('E2E 주전자 · 한진택배 E2E-TRK-0001')
    const listCallsBefore = captured.listQueries.length
    await dialog.getByTestId('delivered-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('배송완료로 처리했습니다.')
    expect(captured.delivered).toHaveLength(1)
    expect(captured.delivered[0]).toContain(`/api/v1/deliveries/${SHIPPING_ID}/mark-delivered`)
    await expect(dialog).toBeHidden()
    await expect.poll(() => captured.listQueries.length).toBeGreaterThan(listCallsBefore)
  })

  test('② 송장 정정: 현재 값 프리필·사유 필수 → 형식 위반 입력은 클라이언트 문구·PATCH 없음 → 서버 400 fieldErrors → 송장번호 필드 오류(다이얼로그 유지) → 번호 변경 후 재시도 200 → 토스트', async ({ page }) => {
    const captured = await mockSellerDeliveries(page, { rejectFirstPatch: true })
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/deliveries')

    await page.getByTestId('row-menu').click()
    await page.getByTestId('row-correct-tracking').click()
    const dialog = page.getByTestId('seller-tracking-dialog')
    await expect(dialog.getByTestId('tracking-no').locator('input')).toHaveValue('E2E-TRK-0001')
    await expect(dialog.getByTestId('tracking-dialog-ok')).toBeDisabled()
    await pickOption(page, 'tracking-carrier', 'CJ대한통운')
    // v-textarea auto-grow는 높이 계산용 textarea를 하나 더 렌더한다 → 첫 요소만
    await dialog.getByTestId('tracking-reason').locator('textarea').first().fill('택배사 오선택')
    // 형식 위반(자모) → 제출 전 클라이언트 문구·PATCH 없음
    await dialog.getByTestId('tracking-no').locator('input').fill('ㅗㅗㅗ')
    await dialog.getByTestId('tracking-dialog-ok').click()
    await expect(dialog).toContainText(TRACKING_NO_FORMAT_MESSAGE)
    expect(captured.patches).toHaveLength(0)

    // 형식은 맞지만 서버가 400 fieldErrors로 거부 → 서버 문구를 필드 오류로 표시(다이얼로그 유지)
    await dialog.getByTestId('tracking-no').locator('input').fill('E2E-TRK-0001')
    await expect(dialog).not.toContainText(TRACKING_NO_FORMAT_MESSAGE) // 입력 수정 시 클라이언트 오류가 지워진다 → 아래 문구는 서버 응답
    await dialog.getByTestId('tracking-dialog-ok').click()
    await expect(dialog).toContainText(TRACKING_NO_FORMAT_MESSAGE)
    await expect(dialog).toBeVisible()
    expect(captured.patches).toHaveLength(1)
    expect(captured.patches[0]?.body).toEqual({ carrier: 'CJ', trackingNo: 'E2E-TRK-0001', reason: '택배사 오선택' })

    await dialog.getByTestId('tracking-no').locator('input').fill('E2E-TRK-FIX')
    await dialog.getByTestId('tracking-dialog-ok').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('송장 정보를 수정했습니다.')
    expect(captured.patches).toHaveLength(2)
    expect(captured.patches[1]?.url).toContain(`/api/v1/seller/deliveries/${SHIPPING_ID}/tracking`)
    await expect(dialog).toBeHidden()
  })
})
