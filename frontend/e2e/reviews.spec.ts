import { test, expect, type Page, type Request } from '@playwright/test'
import { loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'

/**
 * 상품 리뷰 구매자 화면(Track 106-1 PR2) E2E. 주문·리뷰·상품 API는 page.route로 mock해 로컬 DB를 바꾸지 않는다(claims.spec 패턴).
 * 구매자 SSR 페이지는 gotoClientSide로 들어가 useFetch·useAsyncData가 브라우저에서 실행되게 한다(SSR 조회는 page.route를 거치지 않음).
 * ① 주문 상세 별점 → 작성 페이지(별점 query 채움) → 키워드 · 사진 1장씩 2회 · 본문 → 등록 본문 · 완료
 * ② 작성한 리뷰: 숨김 배지·사유 · 숨김 리뷰 수정 잠김 · 수정 저장 422 안내
 * ③ 상품 상세 리뷰 섹션: 요약 · 사진 띠 → 라이트박스 · 사진만 필터 재조회 · 더보기 · 비로그인 도움됐어요 → 로그인
 * ④ 상품 목록 카드 별점(0건이면 줄 없음)
 */
const ORDER_ID = 'ord_E2E00000000000000000001061'
const WRITABLE_ITEM = 'oit_E2E00000000000000000001061'
const HIDDEN_ITEM = 'oit_E2E00000000000000000001062'
const VISIBLE_ITEM = 'oit_E2E00000000000000000001063'
const PRODUCT_ID = 'prd_E2E00000000000000000001061'
const HIDDEN_REVIEW = 'rvw_E2E00000000000000000001062'
const VISIBLE_REVIEW = 'rvw_E2E00000000000000000001063'
const CREATED_REVIEW = 'rvw_E2E00000000000000000001069'
const ATT_1 = 'att_E2E00000000000000000001061'
const ATT_2 = 'att_E2E00000000000000000001062'
const PNG_1X1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')
const SCREENSHOT_DIR = 'playwright-report/track-106-1'
const MOBILE_VIEWPORT = { width: 390, height: 844 }
const DESKTOP_VIEWPORT = { width: 1280, height: 900 }

function confirmedItem(orderItemId: string, review: Record<string, unknown>) {
  return {
    orderItemId, productId: PRODUCT_ID, productName: 'E2E 린넨 셔츠', optionLabel: '색상: 블랙 / 사이즈: M', quantity: 1,
    unitPrice: 49000, totalPrice: 49000, status: { code: 'CONFIRMED', label: 'CONFIRMED' }, review,
  }
}

const ORDER_DETAIL = {
  orderId: ORDER_ID,
  status: { code: 'CONFIRMED', label: 'CONFIRMED' },
  sellers: [{
    sellerId: 'slr_E2E1', companyName: 'E2E셀러', subtotal: 147000,
    items: [
      confirmedItem(WRITABLE_ITEM, { status: 'WRITABLE' }),
      confirmedItem(HIDDEN_ITEM, { status: 'WRITTEN', reviewId: HIDDEN_REVIEW, hidden: true }),
      confirmedItem(VISIBLE_ITEM, { status: 'WRITTEN', reviewId: VISIBLE_REVIEW, hidden: false }),
    ],
  }],
  totalPrice: 147000,
  shippingAddress: null,
}

const KEYWORD_OPTIONS = [
  { code: 'DELIVERY_FAST', label: '배송이 빨라요', groupCode: 'DELIVERY', sortOrder: 1 },
  { code: 'QUALITY_GOOD', label: '품질이 좋아요', groupCode: 'QUALITY', sortOrder: 3 },
  { code: 'APPAREL_SIZE_FIT', label: '사이즈가 딱 맞아요', groupCode: 'PRODUCT', sortOrder: 102 },
]

function ownReview(reviewId: string, status: 'VISIBLE' | 'HIDDEN') {
  return {
    reviewId, rating: 5, content: '기존 리뷰 본문', optionLabel: '색상: 블랙 / 사이즈: M',
    keywords: [{ code: 'QUALITY_GOOD', label: '품질이 좋아요' }],
    photos: [{ attachmentId: 'att_E2E00000000000000000001068', url: '/api/v1/files/reviews/2026/09/E2E-OLD.png', thumbnailUrl: '/api/v1/files/reviews/2026/09/E2E-OLD.png' }],
    status, photosPublic: status === 'VISIBLE', createdAt: '2026-09-20T12:00:00.000+09:00',
    ...(status === 'HIDDEN' ? { hiddenReason: '광고성 게시물' } : {}),
  }
}

interface Captured {
  uploads: Request[]
  created: Record<string, unknown>[]
}

/** 요청 본문의 multipart 파일 파트 수(요청당 1장인지 확인). */
function filePartCount(request: Request): number {
  return (request.postDataBuffer()?.toString('latin1').match(/name="files"/g) ?? []).length
}

async function mockWriteApis(page: Page): Promise<Captured> {
  const captured: Captured = { uploads: [], created: [] }
  await page.route((url) => /\/api\/v1\/files\/reviews\//.test(url.pathname), (route) => route.fulfill({ body: PNG_1X1, contentType: 'image/png' }))
  await page.route((url) => /\/api\/v1\/orders\/ord_[^/]+$/.test(url.pathname), (route) => route.fulfill({ json: ORDER_DETAIL }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: KEYWORD_OPTIONS }))
  await page.route((url) => url.pathname === '/api/v1/reviews/attachments', (route) => {
    captured.uploads.push(route.request())
    const attachmentId = captured.uploads.length === 1 ? ATT_1 : ATT_2
    return route.fulfill({ json: { results: [{ fileName: `p${captured.uploads.length}.png`, success: true, attachmentId, url: `/api/v1/files/reviews/2026/09/${attachmentId}.png`, thumbnailUrl: `/api/v1/files/reviews/2026/09/${attachmentId}.png` }], successCount: 1, failureCount: 0 } })
  })
  await page.route((url) => url.pathname === '/api/v1/reviews', (route) => {
    captured.created.push(JSON.parse(route.request().postData() ?? '{}') as Record<string, unknown>)
    return route.fulfill({ status: 201, json: { reviewId: CREATED_REVIEW } })
  })
  await page.route((url) => /\/api\/v1\/reviews\/rvw_[^/]+$/.test(url.pathname), (route) => {
    const hidden = route.request().url().includes(HIDDEN_REVIEW)
    if (route.request().method() === 'PUT') {
      // 수정 직전 관리자가 숨김 처리한 경우(BE 422 REVIEW_INVALID_STATE)
      return route.fulfill({ status: 422, json: { status: 422, code: 'REVIEW_INVALID_STATE', detail: '숨김 처리된 리뷰는 수정할 수 없습니다.' } })
    }
    return route.fulfill({ json: ownReview(hidden ? HIDDEN_REVIEW : VISIBLE_REVIEW, hidden ? 'HIDDEN' : 'VISIBLE') })
  })
  return captured
}

test.describe('리뷰 작성·수정(Track 106-1)', () => {
  test('① 주문 상세 별점 4 → 작성 페이지(별점 채움) → 키워드 · 사진 1장씩 2회 · 본문 → 등록 본문 · 완료 → 상품 페이지 링크', async ({ page }) => {
    const captured = await mockWriteApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/orders/${ORDER_ID}`)
    await expect(page.getByTestId('item-review-prompt')).toHaveCount(1)
    await expect(page.getByTestId('item-review-prompt')).toContainText('방금 확정한 상품, 어땠나요?')
    await page.getByTestId('item-review-star-4').click()

    await page.waitForURL(/\/reviews\/new\?/)
    expect(page.url()).toContain(`orderItem=${WRITABLE_ITEM}`)
    expect(page.url()).toContain('rating=4')
    await expect(page.getByTestId('rating-input-4')).toBeChecked()
    await expect(page.getByTestId('review-rating-label')).toHaveText('좋아요')
    await expect(page.getByTestId('review-product-name')).toHaveText('E2E 린넨 셔츠')

    await page.getByTestId('review-keyword-APPAREL_SIZE_FIT').click()
    await expect(page.getByTestId('review-keyword-APPAREL_SIZE_FIT')).toHaveAttribute('aria-pressed', 'true')
    await page.getByTestId('photo-input-file').setInputFiles([
      { name: 'a.png', mimeType: 'image/png', buffer: PNG_1X1 },
      { name: 'b.png', mimeType: 'image/png', buffer: PNG_1X1 },
    ])
    await expect(page.getByTestId('photo-input-item')).toHaveCount(2)
    expect(captured.uploads).toHaveLength(2)
    expect(captured.uploads.map(filePartCount)).toEqual([1, 1])
    // 미리보기는 로컬 object URL(연결 전 서버 URL은 404)
    await expect(page.getByTestId('photo-input-item').first().locator('img')).toHaveAttribute('src', /^blob:/)
    await page.getByTestId('review-content').fill('핏이 좋고 소재가 시원해요.')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/review-form-desktop.png`, fullPage: true })
    await page.setViewportSize(MOBILE_VIEWPORT)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/review-form-mobile.png`, fullPage: true })

    await page.getByTestId('review-submit').click()
    await expect(page.getByTestId('review-submitted')).toBeVisible()
    expect(captured.created).toEqual([{
      orderItemId: WRITABLE_ITEM, rating: 4, keywordCodes: ['APPAREL_SIZE_FIT'], content: '핏이 좋고 소재가 시원해요.', attachmentIds: [ATT_1, ATT_2],
    }])
    await expect(page.getByTestId('review-submitted-product-link')).toHaveAttribute('href', `/products/${PRODUCT_ID}`)
  })

  test('② 작성한 리뷰: 숨김이면 배지·사유 · 숨김 리뷰 수정 화면 잠김 / 공개 리뷰는 초기값 채움 → 저장 422 안내', async ({ page }) => {
    await mockWriteApis(page)
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/orders/${ORDER_ID}`)
    await expect(page.getByTestId('item-review-hidden')).toHaveCount(1)
    await expect(page.getByTestId('item-review-hidden')).toContainText('비공개 처리됨')
    await expect(page.getByTestId('item-review-hidden')).toContainText('광고성 게시물')
    await expect(page.getByTestId('item-review-edit')).toHaveCount(2)

    await page.getByTestId('item-review-edit').first().click()
    await page.waitForURL(new RegExp(`/reviews/${HIDDEN_REVIEW}/edit`))
    await expect(page.getByTestId('review-locked')).toContainText('비공개 처리된 리뷰는 수정할 수 없습니다.')
    await expect(page.getByTestId('review-submit')).toHaveCount(0)

    await gotoClientSide(page, `/orders/${ORDER_ID}`)
    await page.getByTestId('item-review-edit').nth(1).click()
    await page.waitForURL(new RegExp(`/reviews/${VISIBLE_REVIEW}/edit`))
    await expect(page.getByTestId('rating-input-5')).toBeChecked()
    await expect(page.getByTestId('review-content')).toHaveValue('기존 리뷰 본문')
    await expect(page.getByTestId('review-keyword-QUALITY_GOOD')).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByTestId('photo-input-item')).toHaveCount(1)
    await page.getByTestId('review-submit').click()
    await expect(page.getByTestId('review-error')).toHaveText('비공개 처리된 리뷰는 수정할 수 없습니다.')
  })
})

// ---------------------------------------------------------------------------
// 상품 상세 · 목록
// ---------------------------------------------------------------------------
const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 린넨 셔츠', description: '시원한 린넨 셔츠', categoryId: 3, categoryName: '의류', sellerName: 'E2E셀러',
  sellerPublicId: 'slr_E2E00000000000000000001061', displayPrice: 49000, soldOut: false, saleStopped: false, images: [],
  optionGroups: [{ name: '색상', displayOrder: 0, values: [{ value: '블랙', displayOrder: 0 }, { value: '화이트', displayOrder: 1 }] }],
  variants: [
    { variantPublicId: 'var_E2E1', salePrice: 49000, soldOut: false, options: [{ groupName: '색상', value: '블랙' }] },
    { variantPublicId: 'var_E2E2', salePrice: 49000, soldOut: false, options: [{ groupName: '색상', value: '화이트' }] },
  ],
}
const PHOTO = (index: number) => ({ url: `/api/v1/files/reviews/2026/09/E2E-${index}.png`, thumbnailUrl: `/api/v1/files/reviews/2026/09/E2E-${index}.png` })
const SUMMARY = {
  reviewCount: 3,
  averageRating: 4.3,
  ratingDistribution: [{ rating: 5, count: 2 }, { rating: 4, count: 0 }, { rating: 3, count: 1 }, { rating: 2, count: 0 }, { rating: 1, count: 0 }],
  keywords: [{ code: 'APPAREL_SIZE_FIT', label: '사이즈가 딱 맞아요', count: 2 }, { code: 'QUALITY_GOOD', label: '품질이 좋아요', count: 1 }],
  recentPhotos: [1, 2, 3].map((index) => ({ reviewId: `rvw_E2E${index}`, ...PHOTO(index) })),
  summaryText: '평균 4.3점 · 사이즈가 딱 맞아요 · 품질이 좋아요',
}

function listItem(index: number, photos: number) {
  return {
    reviewId: `rvw_E2E0000000000000000000107${index}`, rating: index === 3 ? 3 : 5, content: `E2E 리뷰 본문 ${index}`, optionLabel: '색상: 블랙',
    keywords: [{ code: 'APPAREL_SIZE_FIT', label: '사이즈가 딱 맞아요' }], photos: Array.from({ length: photos }, (_, photo) => PHOTO(photo + 1)),
    helpfulCount: index, createdAt: '2026-09-2' + index + 'T12:00:00.000+09:00',
  }
}

async function mockProductApis(page: Page): Promise<URL[]> {
  const listRequests: URL[] = []
  await page.route((url) => /\/api\/v1\/files\/reviews\//.test(url.pathname), (route) => route.fulfill({ body: PNG_1X1, contentType: 'image/png' }))
  await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({ json: { items: [], page: 0, size: 6, totalCount: 0, hasNext: false } }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}`, (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/summary`, (route) => route.fulfill({ json: SUMMARY }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: KEYWORD_OPTIONS }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews`, (route) => {
    const url = new URL(route.request().url())
    listRequests.push(url)
    if (url.searchParams.get('photoOnly') === 'true') {
      return route.fulfill({ json: { items: [listItem(1, 2)], page: 0, size: 10, totalCount: 1, hasNext: false } })
    }
    return route.fulfill({
      json: url.searchParams.get('page') === '1'
        ? { items: [listItem(3, 0)], page: 1, size: 10, totalCount: 3, hasNext: false }
        : { items: [listItem(1, 2), listItem(2, 0)], page: 0, size: 10, totalCount: 3, hasNext: true },
    })
  })
  return listRequests
}

test.describe('상품 상세 리뷰 섹션·목록 카드(Track 106-1)', () => {
  test('③ 요약·말풍선·사진 띠 → 라이트박스(→·Esc) · 사진만 필터 재조회 · 더보기 · 비로그인 도움됐어요 → 로그인', async ({ page }) => {
    const listRequests = await mockProductApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    const section = page.getByTestId('product-reviews')
    await expect(section.getByTestId('product-reviews-count')).toHaveText('3')
    await expect(section.getByTestId('product-reviews-average')).toContainText('4.3')
    await expect(section.getByTestId('product-reviews-summary-text')).toContainText('평균 4.3점')
    await expect(section.getByTestId('product-reviews-keywords').getByTestId('rating-bar')).toHaveCount(2)
    await expect(section.getByTestId('review-card')).toHaveCount(2)
    expect(listRequests[0]!.searchParams.get('sort')).toBe('HELPFUL')
    await section.scrollIntoViewIfNeeded()
    await section.screenshot({ path: `${SCREENSHOT_DIR}/product-reviews-desktop.png` })

    await section.getByTestId('photo-strip-item').first().click()
    await expect(page.getByTestId('lightbox-counter')).toHaveText('1 / 3')
    await page.keyboard.press('ArrowRight')
    await expect(page.getByTestId('lightbox-counter')).toHaveText('2 / 3')
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('lightbox')).toHaveCount(0)

    await section.getByTestId('review-filter-photo').click()
    await expect(section.getByTestId('review-card')).toHaveCount(1)
    expect(listRequests.at(-1)!.searchParams.get('photoOnly')).toBe('true')
    expect(listRequests.at(-1)!.searchParams.get('page')).toBe('0')
    await section.getByTestId('review-filter-all').click()
    await expect(section.getByTestId('review-card')).toHaveCount(2)
    await section.getByTestId('more-button').click()
    await expect(section.getByTestId('review-card')).toHaveCount(3)
    await expect(section.getByTestId('more-button')).toHaveCount(0)

    await page.setViewportSize(MOBILE_VIEWPORT)
    await section.scrollIntoViewIfNeeded()
    await section.screenshot({ path: `${SCREENSHOT_DIR}/product-reviews-mobile.png` })

    await section.getByTestId('review-card-helpful').first().click()
    await page.waitForURL(/\/login\?redirect=/)
  })

  test('④ 목록 카드: 리뷰 있음 → 가격 아래 ★평균 (N) · 0건(averageRating 키 없음) → 줄 없음', async ({ page }) => {
    const card = (index: number, rating: Record<string, unknown>) => ({
      productPublicId: `prd_E2E0000000000000000000108${index}`, name: `E2E 카드 상품 ${index}`, mainImageUrl: null, displayPrice: 10000, soldOut: false,
      categoryId: 3, categoryName: '의류', sellerName: 'E2E셀러', sellerPublicId: 'slr_E2E1', ...rating,
    })
    await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({
      json: { items: [card(1, { averageRating: 4.5, reviewCount: 12 }), card(2, { reviewCount: 0 })], page: 0, size: 20, totalCount: 2, hasNext: false },
    }))
    await gotoClientSide(page, '/products')
    await expect(page.getByTestId('product-card')).toHaveCount(2)
    await expect(page.getByTestId('product-card-rating')).toHaveCount(1)
    await expect(page.getByTestId('product-card-rating')).toContainText('4.5')
    await expect(page.getByTestId('product-card-rating')).toContainText('12')
  })
})
