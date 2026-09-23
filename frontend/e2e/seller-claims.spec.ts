import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { mockSellerMe, pagedResponse, pickOption } from './helpers/seller-mock'

/**
 * 셀러 클레임 화면(Track 90-D-1·조회 전용) E2E. 로그인은 loginAs(SELLER), 클레임 목록·상세·첨부 서빙·품목 목록은 page.route mock.
 * ① 목록(처리 UI 부재) → 유형 필터(URL·API type) → 상세(사유·타임라인·첨부 blob 로드·404 플레이스홀더) → 목록 복귀
 * ② 타 셀러·미존재 클레임 404 → 안내 카드 ③ 주문 품목 행 클레임 칩 → 클레임 상세(back=주문 목록) · 첨부 0장 문구
 */
const RETURN_ID = 'clm_E2E0000000000000000000001'
const CANCEL_ID = 'clm_E2E0000000000000000000002'
const EXCHANGE_ID = 'clm_E2E0000000000000000000003'
const OTHER_ID = 'clm_E2E000000000000000000OTHER'
const ATTACHMENT_OK = '/api/v1/files/claims/2026/09/E2EATTOK00000000000000001.png'
const ATTACHMENT_DENIED = '/api/v1/files/claims/2026/09/E2EATTDENIED000000000001.png'
/** 1×1 PNG(첨부 서빙 mock 본문). */
const PNG_1X1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

const CLAIMS = [
  { claimId: EXCHANGE_ID, type: 'EXCHANGE', status: 'APPROVED', requestedAt: '2026-09-18T11:00:00+09:00', processedAt: '2026-09-18T15:00:00+09:00', orderNo: '20260915-E2E3', productName: 'E2E 주전자', optionLabel: '블랙', reasonCode: 'WRONG_PRODUCT', reasonDetail: '다른 색상이 왔어요', refundStatus: undefined, attachmentCount: 0 },
  { claimId: RETURN_ID, type: 'RETURN', status: 'REQUESTED', requestedAt: '2026-09-17T10:00:00+09:00', orderNo: '20260916-E2E1', productName: 'E2E 반찬통', reasonCode: 'PRODUCT_DEFECT', reasonDetail: '뚜껑이 깨져서 왔습니다', attachmentCount: 2 },
  { claimId: CANCEL_ID, type: 'CANCEL', status: 'COMPLETED', requestedAt: '2026-09-14T09:00:00+09:00', processedAt: '2026-09-14T09:10:00+09:00', orderNo: '20260914-E2E2', productName: 'E2E 컵', reasonCode: 'BUYER_CHANGED_MIND', refundStatus: 'COMPLETED', attachmentCount: 0 },
]

const DETAILS: Record<string, unknown> = {
  [RETURN_ID]: { ...CLAIMS[1], attachments: [{ attachmentId: 'att_E2E1', url: ATTACHMENT_OK }, { attachmentId: 'att_E2E2', url: ATTACHMENT_DENIED }] },
  [EXCHANGE_ID]: { ...CLAIMS[0], attachments: [], exchangeDeliveryStatus: 'SHIPPING' },
  [CANCEL_ID]: { ...CLAIMS[2], attachments: [] },
}

const ORDER_ITEMS = [
  { orderItemId: 'oit_E2E0000000000000000000001', orderNo: '20260916-E2E1', orderedAt: '2026-09-16T09:00:00+09:00', paidAt: '2026-09-16T09:05:00+09:00', productName: 'E2E 반찬통', quantity: 1, unitPrice: 32000, totalPrice: 32000, itemStatus: 'RETURN_REQUESTED', recipientName: '한시우',
    claim: { claimId: RETURN_ID, type: 'RETURN', status: 'REQUESTED', requestedAt: '2026-09-17T10:00:00+09:00' }, claimCount: 2 },
  { orderItemId: 'oit_E2E0000000000000000000002', orderNo: '20260918-E2E4', orderedAt: '2026-09-18T09:00:00+09:00', paidAt: '2026-09-18T09:05:00+09:00', productName: 'E2E 접시', quantity: 1, unitPrice: 12000, totalPrice: 12000, itemStatus: 'PAID', recipientName: '박지훈', claimCount: 0 },
]

interface Captured { listQueries: URLSearchParams[]; attachmentRequests: { url: string; authorization: string | undefined }[] }

async function mockSellerClaims(page: Page): Promise<Captured> {
  const captured: Captured = { listQueries: [], attachmentRequests: [] }
  await mockSellerMe(page)
  await page.route((url) => url.pathname.startsWith('/api/v1/files/claims/'), (route) => {
    captured.attachmentRequests.push({ url: new URL(route.request().url()).pathname, authorization: route.request().headers().authorization })
    if (new URL(route.request().url()).pathname === ATTACHMENT_OK) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1X1, headers: { 'Cache-Control': 'no-store, private' } })
    }
    return route.fulfill({ status: 404, json: { type: 'about:blank', title: 'Not Found', status: 404, code: 'FILE_NOT_FOUND', detail: '파일을 찾을 수 없습니다' } })
  })
  await page.route((url) => /\/api\/v1\/seller\/claims\/clm_[^/]+$/.test(url.pathname), (route) => {
    const claimId = new URL(route.request().url()).pathname.split('/').pop() ?? ''
    const detail = DETAILS[claimId]
    if (!detail) return route.fulfill({ status: 404, json: { type: 'about:blank', title: 'Not Found', status: 404, code: 'CLAIM_NOT_FOUND', detail: '클레임을 찾을 수 없습니다' } })
    return route.fulfill({ json: detail })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/claims'), (route) => {
    const query = new URL(route.request().url()).searchParams
    captured.listQueries.push(query)
    const type = query.get('type')
    const keyword = query.get('keyword')
    let items = type ? CLAIMS.filter((claim) => claim.type === type) : CLAIMS
    if (keyword) items = items.filter((claim) => claim.productName.includes(keyword) || claim.orderNo === keyword)
    return route.fulfill({ json: pagedResponse(items) })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/order-items'), (route) => route.fulfill({ json: pagedResponse(ORDER_ITEMS) }))
  return captured
}

test.describe('셀러 클레임 화면(90-D-1)', () => {
  test('① 목록(3행·처리 버튼 0) → 유형 필터 RETURN(URL·API) → 상세(사유·타임라인·첨부 blob 200/404) → 목록 복귀(필터 유지)', async ({ page }) => {
    const captured = await mockSellerClaims(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/claims')

    await expect(page.getByTestId('seller-claims')).toBeVisible()
    await expect(page.getByTestId('row-claim-type')).toHaveCount(3)
    await expect(page.getByTestId('row-claim-type').nth(0)).toHaveText('교환')
    await expect(page.getByTestId('status-chip').nth(0)).toHaveText('승인')
    await expect(page.getByTestId('status-chip').nth(1)).toHaveClass(/slr-chip--warning/) // RETURN REQUESTED
    await expect(page.getByTestId('refund-status-chip')).toHaveCount(1) // CANCEL COMPLETED 행만
    await expect(page.getByTestId('row-attachment-count')).toHaveCount(1)
    await expect(page.getByTestId('row-attachment-count')).toContainText('첨부 2장')
    await expect(page.getByTestId('row-requested-at').nth(1)).toHaveText('2026.09.17 10:00')
    // 조회 전용: 처리 UI(승인·거부·검수·발송) 부재 · 사이드바 클레임 활성
    await expect(page.getByTestId('seller-claims').getByRole('button', { name: /승인|거부|검수|발송/ })).toHaveCount(0)
    await expect(page.getByTestId('seller-claims').locator('[data-testid="row-prepare-shipment"]')).toHaveCount(0)
    await expect(page.getByTestId('seller-sidebar').locator('.v-list-item--active')).toContainText('클레임')
    expect(captured.listQueries[0]?.get('page')).toBe('0')
    expect(captured.listQueries[0]?.has('type')).toBe(false)

    // 유형 필터 → URL·API type=RETURN·1행
    await pickOption(page, 'filter-type', '반품')
    await expect(page).toHaveURL(/type=RETURN/)
    await expect(page.getByTestId('row-claim-type')).toHaveCount(1)
    await expect.poll(() => captured.listQueries.at(-1)?.get('type')).toBe('RETURN')

    // 상세: 유형 링크 클릭 → back=목록 URL · 사유·상세 사유 · 타임라인(요청 done·처리 대기 current) · 첨부 2장(200 → 썸네일 blob / 404 → 플레이스홀더)
    await page.getByTestId('row-claim-type').first().click()
    await page.waitForURL(new RegExp(`/seller/claims/${RETURN_ID}\\?back=`))
    await expect(page.getByTestId('claim-detail-status')).toHaveText('요청')
    await expect(page.getByTestId('claim-detail-reason')).toHaveText('상품 불량')
    await expect(page.getByTestId('claim-detail-reason-detail')).toHaveText('뚜껑이 깨져서 왔습니다')
    await expect(page.getByTestId('claim-detail-order-no')).toHaveText('20260916-E2E1')
    await expect(page.getByTestId('claim-timeline-requested')).toContainText('요청 접수')
    await expect(page.getByTestId('claim-timeline-requested')).toContainText('2026.09.17 10:00')
    await expect(page.getByTestId('claim-timeline-processed')).toContainText('관리자 처리 대기')
    await expect(page.getByTestId('seller-claim-detail').getByRole('button', { name: /승인|거부|검수/ })).toHaveCount(0)
    await expect(page.getByTestId('claim-attachment')).toHaveCount(2)
    const thumb = page.getByTestId('claim-attachment-thumb')
    await expect(thumb).toHaveCount(1)
    await expect(thumb.locator('img')).toHaveAttribute('src', /^blob:/)
    await expect(page.getByTestId('claim-attachment-error')).toHaveCount(1)
    await expect(page.getByTestId('claim-attachment-error')).toContainText('열람 권한이 없거나 삭제된 사진')
    await expect.poll(() => captured.attachmentRequests.length).toBe(2)
    expect(captured.attachmentRequests.every((request) => request.authorization?.startsWith('Bearer '))).toBe(true)
    // 확대: 썸네일 클릭 → 다이얼로그 blob img → 닫기
    await thumb.click()
    await expect(page.getByTestId('claim-attachment-preview').locator('img')).toHaveAttribute('src', /^blob:/)
    await page.getByTestId('claim-attachment-preview').getByRole('button', { name: '닫기' }).click()
    await expect(page.getByTestId('claim-attachment-preview')).toHaveCount(0)

    await page.getByTestId('claim-detail-back').click()
    await expect(page).toHaveURL(/\/seller\/claims\?type=RETURN$/)
    await expect(page.getByTestId('row-claim-type')).toHaveCount(1)
  })

  test('② 타 셀러·미존재 클레임 → 404 안내 카드 → 목록으로', async ({ page }) => {
    await mockSellerClaims(page)
    await loginAs(page, 'SELLER')
    await page.goto(`/seller/claims/${OTHER_ID}`)
    await expect(page.getByTestId('claim-detail-not-found')).toBeVisible()
    await expect(page.getByTestId('claim-detail-not-found')).toContainText('내 품목의 클레임이 아니거나')
    await page.getByTestId('claim-detail-not-found').getByRole('link', { name: '목록으로' }).click()
    await expect(page).toHaveURL(/\/seller\/claims$/)
  })

  test('③ 주문 품목 행 클레임 칩("반품 요청 · 2건") → 클레임 상세(back=주문 목록) · 교환 상세는 교환품 배송 상태·첨부 0장 문구', async ({ page }) => {
    await mockSellerClaims(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/orders')
    await expect(page.getByTestId('row-order-no')).toHaveCount(2)
    await expect(page.getByTestId('claim-chip')).toHaveCount(1)
    await expect(page.getByTestId('claim-chip')).toHaveText('반품 요청 · 2건')
    await page.getByTestId('claim-chip').click()
    await page.waitForURL(new RegExp(`/seller/claims/${RETURN_ID}\\?back=`))
    await expect(page.getByTestId('claim-detail-product')).toHaveText('E2E 반찬통')
    await page.getByTestId('claim-detail-back').click()
    await expect(page).toHaveURL(/\/seller\/orders$/)

    await page.goto(`/seller/claims/${EXCHANGE_ID}`)
    await expect(page.getByTestId('claim-detail-status')).toHaveText('승인')
    await expect(page.getByTestId('claim-detail-exchange-delivery')).toHaveText('배송중')
    await expect(page.getByTestId('claim-timeline-completed')).toContainText('처리 진행 중')
    await expect(page.getByTestId('claim-detail-no-attachments')).toContainText('첨부된 사진이 없습니다')
    await expect(page.getByTestId('claim-attachment')).toHaveCount(0)
  })
})
