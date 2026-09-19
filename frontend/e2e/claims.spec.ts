import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'

/**
 * 사용자 반품 화면(FE-29) E2E. 로그인은 공용 헬퍼 loginAs(BUYER_E2E_* 주입·미주입 시 skip)이며 gotoClientSide로 클라이언트 내비게이션해
 * 이후 useFetch가 브라우저에서 실행되도록 한다(SSR fetch는 page.route를 거치지 않음). 주문·클레임 API는 page.route로 mock해 로컬 DB를 바꾸지 않는다.
 */
const ORDER_ID = 'ord_E2E00000000000000000000201'
const SHIPPING_ITEM = 'oit_E2E00000000000000000000201'
const DELIVERED_ITEM = 'oit_E2E00000000000000000000202'
const CLAIM_ID = 'clm_E2E00000000000000000000201'
const ATT_1 = 'att_E2E00000000000000000000001'
const ATT_2 = 'att_E2E00000000000000000000002'
const EXCHANGED_ITEM = 'oit_E2E00000000000000000000203'
const PRODUCT_ID = 'prd_E2E00000000000000000000201'
const VARIANT_RED = 'var_E2E00000000000000000000201'
const VARIANT_BLUE = 'var_E2E00000000000000000000202'
const VARIANT_PRICED = 'var_E2E00000000000000000000203'
const VARIANT_SOLDOUT = 'var_E2E00000000000000000000204'
/** 상품 상세 mock(FE-30 교환 옵션 후보): 같은 가격 파랑만 후보·노랑은 가격 다름·검정은 품절·빨강은 현재 옵션. */
const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 배송완료 양말', description: null, categoryId: 1, categoryName: '양말', sellerName: 'E2E셀러',
  displayPrice: 10000, soldOut: false, saleStopped: false, images: [], optionGroups: [{ name: '색상', values: [] }],
  variants: [
    { variantPublicId: VARIANT_RED, salePrice: 10000, soldOut: false, options: [{ groupName: '색상', value: '빨강' }] },
    { variantPublicId: VARIANT_BLUE, salePrice: 10000, soldOut: false, options: [{ groupName: '색상', value: '파랑' }] },
    { variantPublicId: VARIANT_PRICED, salePrice: 10500, soldOut: false, options: [{ groupName: '색상', value: '노랑' }] },
    { variantPublicId: VARIANT_SOLDOUT, salePrice: 10000, soldOut: true, options: [{ groupName: '색상', value: '검정' }] },
  ],
}
const PNG_1X1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

const ORDER_DETAIL = {
  orderId: ORDER_ID,
  status: { code: 'DELIVERED', label: 'DELIVERED' },
  sellers: [{
    sellerId: 'slr_E2E1', companyName: 'E2E셀러', subtotal: 29900,
    items: [
      { orderItemId: SHIPPING_ITEM, productName: 'E2E 배송중 티셔츠', quantity: 1, unitPrice: 19900, totalPrice: 19900, status: { code: 'SHIPPING', label: 'SHIPPING' } },
      { orderItemId: DELIVERED_ITEM, productName: 'E2E 배송완료 양말', productId: PRODUCT_ID, variantId: VARIANT_RED, quantity: 1, unitPrice: 10000, totalPrice: 10000, status: { code: 'DELIVERED', label: 'DELIVERED' } },
      { orderItemId: EXCHANGED_ITEM, productName: 'E2E 교환완료 모자', productId: PRODUCT_ID, variantId: VARIANT_BLUE, quantity: 1, unitPrice: 10000, totalPrice: 10000, status: { code: 'DELIVERED', label: 'DELIVERED' }, exchangeCompleted: true },
    ],
  }],
  totalPrice: 29900,
  shippingAddress: null,
}

const RETURN_SHIPMENT = { deliveryPublicId: 'dlv_E2E1', direction: 'RETURN', carrier: 'CJ', trackingNo: 'RTN-0001', status: 'SHIPPING', shippedAt: '2026-09-16T12:00:00+09:00', deliveredAt: null }

function claimDetail(overrides: Record<string, unknown>) {
  return {
    publicId: CLAIM_ID, orderItemPublicId: DELIVERED_ITEM, claimType: 'RETURN', status: 'APPROVED', reasonCode: 'PRODUCT_DEFECT', reasonDetail: '올 풀림',
    requestedAt: '2026-09-16T10:00:00+09:00', processedAt: '2026-09-16T11:00:00+09:00', returnShipmentRequired: true,
    attachmentUrls: ['/api/v1/files/claims/2026/09/E2E1.png'], ...overrides,
  }
}

interface Captured { posts: { url: string; body: string; contentType: string }[] }

async function mockApis(page: Page, detail: Record<string, unknown>): Promise<Captured> {
  const captured: Captured = { posts: [] }
  await page.route((url) => /\/api\/v1\/files\/claims\//.test(url.pathname), (route) => route.fulfill({ body: PNG_1X1, contentType: 'image/png' }))
  await page.route((url) => /\/api\/v1\/orders\/ord_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: ORDER_DETAIL }))
  await page.route((url) => /\/api\/v1\/products\/prd_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname.endsWith('/api/v1/claims/attachments'), (route) => {
    captured.posts.push({ url: route.request().url(), body: '', contentType: route.request().headers()['content-type'] ?? '' })
    const nth = captured.posts.filter((post) => post.url.endsWith('/attachments')).length
    const attachmentId = nth === 1 ? ATT_1 : ATT_2
    return route.fulfill({ json: { results: [{ fileName: `p${nth}.png`, success: true, attachmentId, url: `/api/v1/files/claims/2026/09/E2E${nth}.png`, thumbnailUrl: `/api/v1/files/claims/2026/09/E2E${nth}.png` }], successCount: 1, failureCount: 0 } })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/claims') && !url.searchParams.has('page'), (route) => {
    if (route.request().method() !== 'POST') return route.fulfill({ json: { items: [], page: 0, size: 20, totalCount: 0, hasNext: false } })
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '', contentType: '' })
    return route.fulfill({ status: 201, headers: { Location: `/api/v1/claims/${CLAIM_ID}` }, json: claimDetail({ status: 'REQUESTED', returnShipmentRequired: false }) })
  })
  await page.route((url) => /\/api\/v1\/claims\/clm_[^/]+\/return-shipment$/.test(url.pathname), (route) => {
    captured.posts.push({ url: route.request().url(), body: route.request().postData() ?? '', contentType: '' })
    return route.fulfill({ json: RETURN_SHIPMENT })
  })
  let detailCalls = 0
  await page.route((url) => /\/api\/v1\/claims\/clm_[^/?]+$/.test(url.pathname), (route) => {
    detailCalls += 1
    // 회수 송장 등록 후 재조회는 송장 등록 상태로 응답한다.
    const registered = captured.posts.some((post) => post.url.endsWith('/return-shipment'))
    return route.fulfill({ json: registered ? { ...detail, returnShipmentRequired: false, returnShipment: RETURN_SHIPMENT } : detail })
  })
  void detailCalls
  return captured
}

test.describe('사용자 반품 요청·회수·검수(FE-29)', () => {
  test('① 주문 상세: 배송중 품목 반품 버튼 없음·배송완료만 → 반품 요청 사유 3값 → 불량 선택 시 첨부 섹션·2장 업로드(순서) → 제출 body attachmentIds / 단순변심으로 바꾸면 첨부 해제 / 스크린샷', async ({ page }) => {
    const captured = await mockApis(page, claimDetail({}))
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/orders/${ORDER_ID}`)
    await expect(page.getByText('E2E 배송완료 양말')).toBeVisible()
    await expect(page.getByRole('button', { name: '반품 요청' })).toHaveCount(2) // 배송완료 + 교환 완료(반품만) 품목
    await expect(page.getByRole('button', { name: '교환 요청' })).toHaveCount(1)
    await expect(page.getByRole('button', { name: '취소 요청' })).toHaveCount(0)

    await page.getByRole('button', { name: '반품 요청' }).first().click()
    await page.waitForURL(/\/claims\/new\?/)
    expect(page.url()).toContain(`orderItem=${DELIVERED_ITEM}`)
    const options = page.locator('#reasonCode option:not([disabled])')
    await expect(options).toHaveCount(3)
    expect(await options.allTextContents()).toEqual(['단순 변심', '상품 불량', '오배송'])
    await expect(page.getByTestId('claim-attachment-input')).toHaveCount(0)

    await page.locator('#reasonCode').selectOption('PRODUCT_DEFECT')
    await expect(page.getByTestId('claim-attachment-input')).toBeVisible()
    await page.getByTestId('claim-attachment-file-input').setInputFiles([{ name: 'p1.png', mimeType: 'image/png', buffer: PNG_1X1 }])
    await expect(page.getByTestId('claim-attachment-list').locator('li')).toHaveCount(1)
    await page.getByTestId('claim-attachment-file-input').setInputFiles([{ name: 'p2.png', mimeType: 'image/png', buffer: PNG_1X1 }])
    await expect(page.getByTestId('claim-attachment-list').locator('li')).toHaveCount(2)
    expect(captured.posts.filter((post) => post.url.endsWith('/attachments'))).toHaveLength(2)
    expect(captured.posts[0]!.contentType).toContain('multipart/form-data')
    await expect(page.getByTestId('claim-attachment-input')).toContainText('2/5')
    await page.screenshot({ path: 'playwright-report/fe-29/claim-new-return-attachments.png' })

    // 단순변심으로 바꾸면 첨부 섹션·목록 해제 → 다시 불량으로 바꿔도 비어 있음
    await page.locator('#reasonCode').selectOption('BUYER_CHANGED_MIND')
    await expect(page.getByTestId('claim-attachment-input')).toHaveCount(0)
    await page.locator('#reasonCode').selectOption('WRONG_PRODUCT')
    await expect(page.getByTestId('claim-attachment-list')).toHaveCount(0)
    await page.getByTestId('claim-attachment-file-input').setInputFiles([{ name: 'p3.png', mimeType: 'image/png', buffer: PNG_1X1 }])
    await expect(page.getByTestId('claim-attachment-list').locator('li')).toHaveCount(1)

    await page.getByRole('button', { name: '반품 요청하기' }).click()
    await expect(page.getByText('클레임이 접수되었습니다.')).toBeVisible()
    const submit = captured.posts.find((post) => post.url.endsWith('/api/v1/claims'))!
    expect(JSON.parse(submit.body)).toEqual({ orderItemPublicId: DELIVERED_ITEM, claimType: 'RETURN', reasonCode: 'WRONG_PRODUCT', attachmentIds: [ATT_2] }) // 3번째 업로드 mock 응답 = ATT_2
  })

  test('② 클레임 상세(승인·회수 송장 대기): 6단 타임라인·첨부 사진 → 택배사·송장 등록 → POST body → 재조회로 폼 사라짐·회수 송장 표기', async ({ page }) => {
    const captured = await mockApis(page, claimDetail({}))
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/claims/${CLAIM_ID}`)
    const steps = page.locator('ol li')
    await expect(steps).toHaveCount(6)
    await expect(steps.nth(1)).toContainText('승인')
    await expect(steps.nth(5)).toContainText('환불 완료')
    await expect(page.getByTestId('claim-attachments').locator('img')).toHaveCount(1)
    const form = page.getByTestId('claim-return-shipment-form')
    await expect(form).toBeVisible()

    // 택배사·송장은 native required(브라우저 검증)라 빈 제출은 POST가 나가지 않는다
    await form.getByTestId('claim-return-shipment-submit').click()
    expect(captured.posts.filter((entry) => entry.url.endsWith('/return-shipment'))).toHaveLength(0)
    await form.locator('#shipmentCarrier').selectOption('CJ')
    await form.locator('#shipmentTrackingNo').fill('RTN-0001')
    await form.getByTestId('claim-return-shipment-submit').click()
    await expect(page.getByTestId('claim-return-shipment')).toContainText('CJ대한통운 RTN-0001')
    await expect(page.getByTestId('claim-return-shipment-form')).toHaveCount(0)
    const post = captured.posts.find((entry) => entry.url.endsWith('/return-shipment'))!
    expect(JSON.parse(post.body)).toEqual({ carrier: 'CJ', trackingNo: 'RTN-0001' })
    await expect(steps.nth(2)).toContainText('회수 송장')
  })

  test('③ 클레임 상세(검수 불합격): 5단 종결·거부 사유 "검수 불합격"·메모·회수 송장·재발송 송장·폼 없음 / 스크린샷', async ({ page }) => {
    await mockApis(page, claimDetail({
      status: 'REJECTED', returnShipmentRequired: false, returnShipment: { ...RETURN_SHIPMENT, status: 'DELIVERED', deliveredAt: '2026-09-17T09:00:00+09:00' },
      pickedUpAt: '2026-09-17T09:00:00+09:00', inspectionResult: 'FAIL', rejectReasonCode: 'INSPECTION_FAILED', rejectMemo: '사용 흔적',
      reshipment: { deliveryPublicId: 'dlv_E2E2', direction: 'OUTBOUND', carrier: 'HANJIN', trackingNo: 'RESHIP-0001', status: 'SHIPPING', shippedAt: '2026-09-17T10:00:00+09:00', deliveredAt: null },
      processedAt: '2026-09-17T10:00:00+09:00',
    }))
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/claims/${CLAIM_ID}`)
    const steps = page.locator('ol li')
    await expect(steps).toHaveCount(5)
    await expect(steps.nth(4)).toContainText('검수 불합격')
    await expect(page.getByTestId('claim-reject-reason')).toHaveText('검수 불합격')
    await expect(page.getByTestId('claim-reject-memo')).toHaveText('사용 흔적')
    await expect(page.getByTestId('claim-inspection-result')).toHaveText('검수 불합격')
    await expect(page.getByTestId('claim-return-shipment')).toContainText('CJ대한통운 RTN-0001')
    await expect(page.getByTestId('claim-reshipment')).toContainText('한진택배 RESHIP-0001')
    await expect(page.getByTestId('claim-return-shipment-form')).toHaveCount(0)
    await page.screenshot({ path: 'playwright-report/fe-29/claim-detail-fail.png', fullPage: true })
  })

  test('④ 교환(FE-30): 교환 완료 품목은 교환 버튼 없음·반품만 → 교환 요청 사유 3값·옵션 후보(같은 가격·품절 제외·현재 제외) 1건·미선택 제출 불가 → 불량 첨부 → body exchangeVariantId', async ({ page }) => {
    const captured = await mockApis(page, claimDetail({}))
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/orders/${ORDER_ID}`)
    await expect(page.getByText('E2E 교환완료 모자')).toBeVisible()
    await expect(page.getByRole('button', { name: '교환 요청' })).toHaveCount(1) // 교환 완료 품목은 숨김
    await expect(page.getByRole('button', { name: '반품 요청' })).toHaveCount(2)

    await page.getByRole('button', { name: '교환 요청' }).click()
    await page.waitForURL(/\/claims\/new\?/)
    expect(page.url()).toContain(`product=${PRODUCT_ID}`)
    expect(page.url()).toContain(`variant=${VARIANT_RED}`)
    expect(page.url()).toContain('unitPrice=10000')
    await expect(page.getByText('같은 가격의 다른 옵션으로만 교환됩니다')).toBeVisible()
    const options = page.locator('#reasonCode option:not([disabled])')
    await expect(options).toHaveCount(3)
    const candidates = page.getByTestId('exchange-option')
    await expect(candidates).toHaveCount(1)
    await expect(page.getByTestId('exchange-options')).toContainText('색상: 파랑')
    await expect(page.getByTestId('exchange-options')).not.toContainText('노랑')
    await expect(page.getByTestId('exchange-options')).not.toContainText('검정')

    await page.locator('#reasonCode').selectOption('PRODUCT_DEFECT')
    await expect(page.getByTestId('claim-attachment-input')).toBeVisible() // 교환도 불량이면 첨부 허용
    await expect(page.getByTestId('claim-submit')).toBeDisabled() // 옵션 미선택
    await candidates.first().check()
    await expect(candidates.first()).toBeChecked()
    await expect(page.getByTestId('claim-submit')).toBeEnabled()
    await page.getByTestId('claim-attachment-file-input').setInputFiles([{ name: 'p1.png', mimeType: 'image/png', buffer: PNG_1X1 }])
    await expect(page.getByTestId('claim-attachment-list').locator('li')).toHaveCount(1)
    await page.screenshot({ path: 'playwright-report/fe-30/claim-new-exchange.png', fullPage: true })
    await page.getByRole('button', { name: '교환 요청하기' }).click()
    await expect(page.getByText('클레임이 접수되었습니다.')).toBeVisible()
    const submit = captured.posts.find((post) => post.url.endsWith('/api/v1/claims'))!
    expect(JSON.parse(submit.body)).toEqual({ orderItemPublicId: DELIVERED_ITEM, claimType: 'EXCHANGE', reasonCode: 'PRODUCT_DEFECT', attachmentIds: [ATT_1], exchangeVariantId: VARIANT_BLUE })
  })

  test('⑤ 교환 클레임 상세(FE-30·승인·회수 송장 대기): 7단 타임라인·교환 옵션 행·교환 회수 안내 / 스크린샷', async ({ page }) => {
    await mockApis(page, claimDetail({ claimType: 'EXCHANGE', originalOptionLabel: '색상: 빨강', exchangeOptionLabel: '색상: 파랑' }))
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/claims/${CLAIM_ID}`)
    const steps = page.locator('ol li')
    await expect(steps).toHaveCount(7)
    await expect(steps.nth(0)).toContainText('신청')
    await expect(steps.nth(1)).toContainText('승인')
    await expect(steps.nth(6)).toContainText('완료')
    await expect(page.getByTestId('claim-exchange-option')).toHaveText('색상: 빨강 → 색상: 파랑')
    await expect(page.getByTestId('claim-return-shipment-form')).toBeVisible()
    await expect(page.getByTestId('claim-return-shipment-guide')).toContainText('교환품을 발송합니다')
    await page.screenshot({ path: 'playwright-report/fe-30/claim-detail-exchange-approved.png', fullPage: true })
  })

  test('⑥ 교환 클레임 상세(FE-30·완료): 7단 완료·교환품 배송 송장·회수 폼 없음 / 스크린샷', async ({ page }) => {
    // 전체 내비게이션(page.goto)은 SSR fetch가 route mock을 거치지 않으므로 별도 테스트(새 컨텍스트)에서 데모 로그인 리다이렉트로 진입한다.
    await mockApis(page, claimDetail({
      claimType: 'EXCHANGE', status: 'COMPLETED', returnShipmentRequired: false, originalOptionLabel: '색상: 빨강', exchangeOptionLabel: '색상: 파랑',
      returnShipment: { ...RETURN_SHIPMENT, status: 'DELIVERED', deliveredAt: '2026-09-17T09:00:00+09:00' }, pickedUpAt: '2026-09-17T09:00:00+09:00', inspectionResult: 'PASS',
      reshipment: { deliveryPublicId: 'dlv_E2E3', direction: 'OUTBOUND', carrier: 'HANJIN', trackingNo: 'EXC-0001', status: 'DELIVERED', shippedAt: '2026-09-17T10:00:00+09:00', deliveredAt: '2026-09-18T10:00:00+09:00' },
      processedAt: '2026-09-18T10:00:00+09:00',
    }))
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/claims/${CLAIM_ID}`)
    await expect(page.locator('ol li')).toHaveCount(7)
    await expect(page.locator('ol li').nth(6)).toContainText('완료')
    await expect(page.getByTestId('claim-reshipment')).toContainText('한진택배 EXC-0001')
    await expect(page.getByText('교환품 배송 송장')).toBeVisible()
    await expect(page.getByTestId('claim-return-shipment-form')).toHaveCount(0)
    await page.screenshot({ path: 'playwright-report/fe-30/claim-detail-exchange-done.png', fullPage: true })
  })
})
