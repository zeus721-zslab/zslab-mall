import { test, expect, type Page } from './fixtures'
import { hasE2eCredentials, loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'

/**
 * 상세 담기 피드백·카테고리 줄 고정 해제(FE-99) E2E. 구매자 세션만 실제 로그인하고 상품·장바구니 API는 page.route로 mock한다
 * (데모 구매자의 실제 장바구니를 바꾸지 않는다).
 * ① 390px: 카테고리 줄이 헤더 밖 · 하단 바로 담기 → 스낵바가 바 위 · 도우미 버튼이 스낵바 위 · 뱃지 1 · 스크롤하면 섹션 바가 헤더 바로 아래
 * ② 1280px: 본문 버튼으로 담기 → 스낵바 노출
 * 스크린샷 3장 → playwright-report/detail-cart-ux(docs/frontend/screens-detail-cart-ux로 옮긴다).
 */
const PRODUCT_ID = 'prd_E2E00000000000000000000990'
const SCREENSHOT_DIR = 'playwright-report/detail-cart-ux'
const DESKTOP_VIEWPORT = { width: 1280, height: 800 }
const MOBILE_VIEWPORT = { width: 390, height: 844 }
const LONG_DESCRIPTION = Array.from({ length: 60 }, (_, index) => `상세 안내 ${index + 1}번째 줄입니다.`).join('\n')

const VARIANT_ID = 'var_E2E990'
const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 담기 셔츠', description: LONG_DESCRIPTION, categoryId: 3, categoryName: '의류',
  sellerName: 'E2E셀러', sellerPublicId: 'slr_E2E00000000000000000000990', displayPrice: 19000, soldOut: false, saleStopped: false, images: [],
  optionGroups: [], variants: [{ variantPublicId: VARIANT_ID, salePrice: 19000, soldOut: false, options: [] }],
}
const CART_ITEM = {
  variantPublicId: VARIANT_ID, quantity: 1, selected: true, productName: PRODUCT_DETAIL.name, sellerName: 'E2E셀러',
  displayPrice: 19000, quantityAvailable: 10, purchasable: true, thumbnailUrl: null,
}
const EMPTY_PAGE = { items: [], page: 0, size: 10, totalCount: 0, hasNext: false }
const EMPTY_REVIEW_SUMMARY = {
  reviewCount: 0, averageRating: 0,
  ratingDistribution: [5, 4, 3, 2, 1].map((rating) => ({ rating, count: 0 })), keywords: [], recentPhotos: [],
}

/** 담기(POST) 전에는 빈 장바구니, 담은 뒤에는 1품목을 돌려준다. */
async function mockApis(page: Page): Promise<void> {
  let cartItems: (typeof CART_ITEM)[] = []
  await page.route((url) => url.pathname === '/api/v1/cart', (route) => route.fulfill({ json: { items: cartItems } }))
  await page.route((url) => url.pathname === '/api/v1/cart/items', (route) => {
    cartItems = [CART_ITEM]
    return route.fulfill({ status: 201, body: '' })
  })
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}`, (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({ json: { ...EMPTY_PAGE, size: 6 } }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/summary`, (route) => route.fulfill({ json: EMPTY_REVIEW_SUMMARY }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: [] }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews`, (route) => route.fulfill({ json: EMPTY_PAGE }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions`, (route) => route.fulfill({ json: EMPTY_PAGE }))
}

function snackbarBox(page: Page) {
  return page.getByTestId('cart-added-snackbar').locator('div').filter({ hasText: '담았어요' })
}

async function rectOf(page: Page, selector: string): Promise<{ top: number; bottom: number }> {
  return page.locator(selector).first().evaluate((element) => {
    const rect = element.getBoundingClientRect()
    return { top: rect.top, bottom: rect.bottom }
  })
}

test.describe('상세 담기 피드백·카테고리 줄(FE-99)', () => {
  test.beforeEach(async ({ page }) => {
    test.skip(!hasE2eCredentials('BUYER'), 'BUYER_E2E_EMAIL / BUYER_E2E_PASSWORD 미설정')
    await loginAs(page, 'BUYER')
    await mockApis(page)
  })

  test('① 390px 하단 바로 담기 → 바 위 스낵바 · 도우미 버튼은 그 위 · 뱃지 1 · 섹션 바는 헤더 바로 아래', async ({ page }) => {
    await page.setViewportSize(MOBILE_VIEWPORT)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await expect(page.getByText(PRODUCT_DETAIL.name).first()).toBeVisible()
    // 카테고리 줄은 고정 헤더 밖(바로 다음 형제)에 있다.
    await expect(page.locator('header + nav[aria-label="카테고리"]')).toBeVisible()

    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await expect(page.locator('body')).toHaveAttribute('data-mobile-action-bar', 'shown')
    const bar = page.locator('div.fixed.inset-x-0.bottom-0.z-40')
    await bar.getByRole('button', { name: '장바구니 담기' }).click()

    await expect(snackbarBox(page)).toBeVisible()
    await expect(page.locator('body')).toHaveAttribute('data-cart-snackbar', 'shown')
    await expect(page.locator('[data-cart-target]')).toContainText('1')
    await expect.poll(async () => {
      const snackbar = await snackbarBox(page).evaluate((element) => element.getBoundingClientRect())
      const barTop = (await rectOf(page, 'div.fixed.inset-x-0.bottom-0.z-40')).top
      const launcherBottom = (await rectOf(page, '[data-testid="faq-launcher"]')).bottom
      return snackbar.bottom <= barTop && launcherBottom <= snackbar.top
    }).toBe(true)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/snackbar-mobile.png` })

    await page.evaluate(() => window.scrollTo(0, 0))
    await page.getByTestId('section-nav-product-description').scrollIntoViewIfNeeded()
    await page.evaluate(() => window.scrollBy(0, 900))
    await expect(page.getByTestId('section-nav-product-description')).toHaveAttribute('aria-current', 'true')
    await expect(page.locator('header + nav[aria-label="카테고리"]')).not.toBeInViewport()
    await expect.poll(async () => {
      const headerBottom = (await rectOf(page, 'header')).bottom
      const navTop = (await rectOf(page, '[data-testid="product-section-nav"]')).top
      return Math.round(navTop) === Math.round(headerBottom)
    }).toBe(true)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/section-nav-mobile.png` })
  })

  test('② 1280px 본문 버튼으로 담기 → 스낵바 노출', async ({ page }) => {
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await page.getByRole('button', { name: '장바구니 담기' }).click()
    await expect(snackbarBox(page)).toBeVisible()
    await expect(snackbarBox(page).getByRole('link', { name: '장바구니 보기' })).toHaveAttribute('href', '/cart')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/snackbar-desktop.png` })
  })
})
