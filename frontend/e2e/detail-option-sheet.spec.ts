import { test, expect, type Page } from './fixtures'
import { hasE2eCredentials, loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'
import { MOCK_PG_ORIGIN } from '../app/lib/constants/payment'

/**
 * 옵션 상품 하단 바 → 옵션 시트 · 바로구매(FE-100) E2E(390px). 구매자 세션만 실제 로그인하고 상품·장바구니·배송지·주문 API는 page.route로
 * mock한다(로컬 DB 무변경). 직접 주문 POST는 order-resume-payment.spec과 같이 mock PG 응답으로 /payment/mock 진입까지 본다.
 * ① 바 [장바구니 담기] → 시트(옵션 미선택 = 두 버튼 비활성) → 옵션 선택 → 담기 → 시트 닫힘 · 스낵바 · POST 본문 variant
 * ② 바 [바로구매] → 시트 → 옵션 선택 → 바로구매 → 주문서 단일 품목(옵션 라벨) → 결제하기 → POST /api/v1/orders 본문 → 모의 결제 화면 · 장바구니 변경 요청 0건
 * 스크린샷 2장 → playwright-report/detail-cart-ux(docs/frontend/screens-detail-cart-ux로 옮긴다).
 */
const PRODUCT_ID = 'prd_E2E00000000000000000001001'
const BLACK_VARIANT = 'var_E2E00000000000000000001001'
const WHITE_VARIANT = 'var_E2E00000000000000000001002'
const ORDER_ID = 'ord_E2E00000000000000000001001'
const ATTEMPT_KEY = 'pat_E2E00000000000000000001001'
const SCREENSHOT_DIR = 'playwright-report/detail-cart-ux'
const MOBILE_VIEWPORT = { width: 390, height: 844 }
const LONG_DESCRIPTION = Array.from({ length: 40 }, (_, index) => `상세 안내 ${index + 1}번째 줄입니다.`).join('\n')

const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 옵션 셔츠', description: LONG_DESCRIPTION, categoryId: 3, categoryName: '의류',
  sellerName: 'E2E셀러', sellerPublicId: 'slr_E2E00000000000000000001001', displayPrice: 21000, soldOut: false, saleStopped: false, images: [],
  optionGroups: [{ name: '색상', displayOrder: 0, values: [{ value: '블랙', displayOrder: 0 }, { value: '화이트', displayOrder: 1 }] }],
  variants: [
    { variantPublicId: BLACK_VARIANT, salePrice: 21000, soldOut: false, options: [{ groupName: '색상', value: '블랙' }] },
    { variantPublicId: WHITE_VARIANT, salePrice: 23000, soldOut: false, options: [{ groupName: '색상', value: '화이트' }] },
  ],
}
const DEFAULT_ADDRESS = {
  id: 1001, isDefault: true, recipientName: 'E2E 구매자', recipientPhone: '010-0000-1001', zonecode: '06236', addressRoad: '서울 강남구 테헤란로 1',
}
const EMPTY_PAGE = { items: [], page: 0, size: 10, totalCount: 0, hasNext: false }
const EMPTY_REVIEW_SUMMARY = {
  reviewCount: 0, averageRating: 0,
  ratingDistribution: [5, 4, 3, 2, 1].map((rating) => ({ rating, count: 0 })), keywords: [], recentPhotos: [],
}

interface Captured {
  cartAddBodies: Record<string, unknown>[]
  cartMutations: string[]
  orderBodies: Record<string, unknown>[]
}

async function mockApis(page: Page): Promise<Captured> {
  const captured: Captured = { cartAddBodies: [], cartMutations: [], orderBodies: [] }
  // 장바구니: GET은 빈 목록, 그 외(담기·선택·결제)는 기록한다.
  await page.route((url) => url.pathname.startsWith('/api/v1/cart'), (route) => {
    const request = route.request()
    if (request.method() === 'GET') return route.fulfill({ json: { items: [] } })
    captured.cartMutations.push(`${request.method()} ${new URL(request.url()).pathname}`)
    if (new URL(request.url()).pathname === '/api/v1/cart/items') captured.cartAddBodies.push(request.postDataJSON() as Record<string, unknown>)
    return route.fulfill({ status: 201, body: '' })
  })
  await page.route((url) => url.pathname === '/api/v1/orders', (route) => {
    captured.orderBodies.push(route.request().postDataJSON() as Record<string, unknown>)
    return route.fulfill({
      status: 201,
      headers: { Location: `/api/v1/orders/${ORDER_ID}` },
      json: {
        payment: {
          publicId: 'pay_E2E00000000000000000001001', status: { code: 'PENDING', label: '결제대기' },
          redirectUrl: `${MOCK_PG_ORIGIN}/checkout?attemptKey=${ATTEMPT_KEY}&amount=23000&method=CARD`, expiresAt: null,
        },
        next: null,
      },
    })
  })
  await page.route((url) => url.pathname === '/api/v1/users/me/addresses', (route) => route.fulfill({ json: [DEFAULT_ADDRESS] }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}`, (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({ json: { ...EMPTY_PAGE, size: 6 } }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/summary`, (route) => route.fulfill({ json: EMPTY_REVIEW_SUMMARY }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: [] }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews`, (route) => route.fulfill({ json: EMPTY_PAGE }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions`, (route) => route.fulfill({ json: EMPTY_PAGE }))
  return captured
}

const BAR_SELECTOR = 'div.fixed.inset-x-0.bottom-0.z-40'

/** 페이지 끝까지 내려 하단 바를 띄우고 바의 버튼을 눌러 시트를 연다. */
async function openSheetFromBar(page: Page, buttonName: string): Promise<void> {
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await expect(page.locator('body')).toHaveAttribute('data-mobile-action-bar', 'shown')
  await page.locator(BAR_SELECTOR).getByRole('button', { name: buttonName }).click()
  await expect(page.getByTestId('product-option-sheet')).toBeVisible()
}

test.describe('옵션 시트·바로구매(FE-100)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!hasE2eCredentials('BUYER'), 'BUYER_E2E_EMAIL / BUYER_E2E_PASSWORD 미설정')
    await loginAs(page, 'BUYER')
    await page.setViewportSize(MOBILE_VIEWPORT)
  })

  test('① 바 장바구니 담기 → 시트(미선택 비활성) → 옵션 선택 → 담기 → 시트 닫힘 · 스낵바 · POST variant', async ({ page }) => {
    const captured = await mockApis(page)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await openSheetFromBar(page, '장바구니 담기')

    const sheet = page.getByTestId('product-option-sheet')
    await expect(sheet.getByRole('button', { name: '장바구니 담기' })).toBeDisabled()
    await expect(sheet.getByRole('button', { name: '바로구매' })).toBeDisabled()

    await sheet.getByRole('button', { name: '블랙' }).click()
    await expect(sheet.getByTestId('product-option-sheet-summary')).toContainText('블랙')
    await expect(sheet.getByTestId('product-option-sheet-summary')).toContainText('21,000')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/option-sheet-mobile.png` })

    await sheet.getByRole('button', { name: '장바구니 담기' }).click()
    await expect(sheet).toBeHidden()
    await expect(page.getByTestId('cart-added-snackbar')).toContainText('담았어요')
    expect(captured.cartAddBodies).toEqual([{ variantPublicId: BLACK_VARIANT, quantity: 1 }])
    // 본문 옵션 칩도 같은 선택을 보여 준다(페이지 단일 상태).
    await expect(page.getByRole('button', { name: '블랙', pressed: true })).toHaveCount(1)
  })

  test('② 바 바로구매 → 시트 → 옵션 선택 → 주문서 단일 품목 → 결제하기 → POST /api/v1/orders → 모의 결제 · 장바구니 변경 0건', async ({ page }) => {
    const captured = await mockApis(page)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await openSheetFromBar(page, '바로구매')

    const sheet = page.getByTestId('product-option-sheet')
    await sheet.getByRole('button', { name: '화이트' }).click()
    await sheet.getByRole('button', { name: '바로구매' }).click()

    await page.waitForURL((url) => url.pathname === '/checkout')
    const query = new URL(page.url()).searchParams
    expect([query.get('product'), query.get('variant'), query.get('quantity')]).toEqual([PRODUCT_ID, WHITE_VARIANT, '1'])
    await expect(page.getByText(PRODUCT_DETAIL.name).first()).toBeVisible()
    await expect(page.getByTestId('item-option-label')).toHaveText('색상: 화이트')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/buy-now-checkout-mobile.png`, fullPage: true })

    await page.locator('aside').getByRole('button', { name: /결제하기/ }).click()
    await page.waitForURL((url) => url.pathname === '/payment/mock')
    expect(new URL(page.url()).searchParams.get('orderPublicId')).toBe(ORDER_ID)
    expect(captured.orderBodies).toHaveLength(1)
    expect(captured.orderBodies[0]).toMatchObject({
      items: [{ productId: PRODUCT_ID, variantId: WHITE_VARIANT, quantity: 1 }],
      shippingAddress: { recipientName: DEFAULT_ADDRESS.recipientName, zonecode: DEFAULT_ADDRESS.zonecode },
      method: 'CARD',
    })
    expect(captured.cartMutations).toEqual([])
  })
})
