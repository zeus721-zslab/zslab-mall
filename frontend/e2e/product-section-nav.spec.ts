import { test, expect, type Page } from './fixtures'
import { gotoClientSide } from './helpers/navigation'

/**
 * 상품 상세 내비게이션(Track 106-2) E2E. 상품·리뷰·질문 API는 page.route로 mock한다(reviews·product-questions.spec 패턴 · 로그인 불필요).
 * 긴 상품 설명으로 리뷰·Q&A가 첫 화면 밖에 있는 상황을 만든다.
 * ① Q&A 칩 → 묻기 섹션이 화면에 들어오고 입력창에 포커스
 * ② 섹션 바 물어보기 → 입력 → 패널에 즉시 답 카드 → 묻기 섹션 입력창에 같은 초안
 * 스크린샷 4장(데스크톱·모바일 × 바 고정 · 물어보기 펼침) → playwright-report/track-106-2(docs/frontend/screens-track-106-2로 옮긴다).
 */
const PRODUCT_ID = 'prd_E2E00000000000000000001063'
const SCREENSHOT_DIR = 'playwright-report/track-106-2'
const DESKTOP_VIEWPORT = { width: 1440, height: 900 }
const MOBILE_VIEWPORT = { width: 390, height: 844 }
const LONG_DESCRIPTION = Array.from({ length: 60 }, (_, index) => `상세 안내 ${index + 1}번째 줄입니다. 소재·세탁·사이즈 정보를 확인하세요.`).join('\n')

const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 긴 설명 셔츠', description: LONG_DESCRIPTION, categoryId: 3, categoryName: '의류',
  sellerName: 'E2E셀러', sellerPublicId: 'slr_E2E00000000000000000001061', displayPrice: 29000, soldOut: false, saleStopped: false, images: [],
  optionGroups: [], variants: [{ variantPublicId: 'var_E2E8', salePrice: 29000, soldOut: false, options: [] }],
}
const REVIEW_SUMMARY = {
  reviewCount: 2, averageRating: 4.5,
  ratingDistribution: [{ rating: 5, count: 1 }, { rating: 4, count: 1 }, { rating: 3, count: 0 }, { rating: 2, count: 0 }, { rating: 1, count: 0 }],
  keywords: [{ code: 'QUALITY_GOOD', label: '품질이 좋아요', count: 2 }], recentPhotos: [],
}
const REVIEW_PAGE = {
  items: [1, 2].map((index) => ({
    reviewId: `rvw_E2E0000000000000000000108${index}`, rating: 5 - index + 1, content: `E2E 리뷰 ${index}`, keywords: [], photos: [], helpfulCount: 0,
    createdAt: '2026-09-2' + index + 'T12:00:00.000+09:00',
  })),
  page: 0, size: 10, totalCount: 2, hasNext: false,
}
const EMPTY_PAGE = { items: [], page: 0, size: 10, totalCount: 0, hasNext: false }
const SUGGESTIONS = [
  { type: 'PRODUCT', text: '상세 안내 3번째 줄입니다. 소재·세탁·사이즈 정보를 확인하세요.' },
  { type: 'REVIEW', text: '세탁해도 줄어들지 않아요', id: 'rvw_E2E00000000000000000001081' },
  { type: 'QNA', text: '세탁 방법이 궁금합니다', answer: '찬물 단독 세탁을 권합니다', id: 'pqn_E2E00000000000000000001081' },
]

async function mockApis(page: Page): Promise<void> {
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}`, (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({ json: { ...EMPTY_PAGE, size: 6 } }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/summary`, (route) => route.fulfill({ json: REVIEW_SUMMARY }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: [] }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews`, (route) => route.fulfill({ json: REVIEW_PAGE }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions`, (route) => route.fulfill({ json: EMPTY_PAGE }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions/suggest`, (route) => route.fulfill({ json: SUGGESTIONS }))
}

/** 설명 중간까지 내려 바가 헤더 아래에 붙은 상태를 만든다. */
async function scrollIntoDescription(page: Page): Promise<void> {
  await page.getByTestId('section-nav-product-description').scrollIntoViewIfNeeded()
  await page.evaluate(() => window.scrollBy(0, 900))
  await expect(page.getByTestId('section-nav-product-description')).toHaveAttribute('aria-current', 'true')
}

async function expandAskAndType(page: Page, text: string): Promise<void> {
  await page.getByTestId('section-nav-ask').click()
  const input = page.getByTestId('section-nav-ask-input')
  await expect(input).toBeFocused()
  await input.fill(text)
  await expect(page.getByTestId('section-nav-panel').getByTestId('product-question-suggestion')).toHaveCount(3)
}

test.describe('상품 상세 내비게이션(Track 106-2)', () => {
  test('① Q&A 칩(0건 = 궁금한 점 물어보기) → 묻기 섹션 도달 · 입력창 포커스', async ({ page }) => {
    await mockApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await expect(page.getByTestId('glance-review')).toContainText('리뷰 2')
    await expect(page.getByTestId('product-questions')).not.toBeInViewport()

    await page.getByTestId('glance-questions').click()
    await expect(page.getByTestId('product-question-input')).toBeFocused()
    await expect(page.getByTestId('product-questions')).toBeInViewport()
    await expect(page.getByTestId('section-nav-product-questions')).toHaveAttribute('aria-current', 'true')
  })

  test('② 바 물어보기 → 입력 → 패널 즉시 답 카드 → 섹션 입력창에 같은 초안 · Esc 접힘(초안 유지) · 스크린샷 4장', async ({ page }) => {
    await mockApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)

    await scrollIntoDescription(page)
    const bar = page.getByTestId('product-section-nav')
    const headerBottom = await page.locator('header').first().evaluate((header) => header.getBoundingClientRect().bottom)
    expect(Math.round(await bar.evaluate((nav) => nav.getBoundingClientRect().top))).toBe(Math.round(headerBottom))
    await page.screenshot({ path: `${SCREENSHOT_DIR}/section-nav-sticky-desktop.png` })

    await expandAskAndType(page, '세탁 되나요')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/section-nav-ask-desktop.png` })
    await expect(page.getByTestId('product-question-input')).toHaveValue('세탁 되나요')

    await page.keyboard.press('Escape')
    await expect(page.getByTestId('section-nav-ask-input')).toHaveCount(0)
    await expect(page.getByTestId('section-nav-ask')).toBeFocused()
    await page.getByTestId('section-nav-ask').click()
    await expect(page.getByTestId('section-nav-ask-input')).toHaveValue('세탁 되나요')
    await page.keyboard.press('Escape')

    await page.setViewportSize(MOBILE_VIEWPORT)
    await scrollIntoDescription(page)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/section-nav-sticky-mobile.png` })
    await page.getByTestId('section-nav-ask').click()
    await expect(page.getByTestId('section-nav-ask-input')).toHaveValue('세탁 되나요')
    await expect(page.getByTestId('section-nav-panel').getByTestId('product-question-suggestion')).toHaveCount(3)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/section-nav-ask-mobile.png` })
  })
})
