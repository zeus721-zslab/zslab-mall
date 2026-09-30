import { test, expect, type Page } from '@playwright/test'
import { gotoClientSide } from './helpers/navigation'
import { loginAs } from './helpers/login'

/**
 * 구매자 채팅 도우미(Track 106-3) E2E. 도우미는 클라이언트 전용이라 FAQ API 호출이 브라우저에서 나가 page.route mock이 적용된다.
 * 상품 상세는 SSR 데이터를 mock해야 하므로 gotoClientSide로 들어간다(product-section-nav 패턴).
 * ① 열기 → 카테고리 → 질문 → 답 → 다른 페이지 이동·새로고침 뒤에도 대화 유지
 * ② 자유 입력 — 결과 칩 · 0건이면 "찾지 못했어요" + 카테고리 칩
 * ③ 상품 상세 첫 칩(배송·취소·반품·교환 강조) · 390px에서 하단 고정 바 위로 버튼이 올라감
 * ④ 제외 경로(모의 결제)에는 버튼이 없음
 * 스크린샷 → playwright-report/track-106-3(docs/frontend/screens-track-106-3로 옮긴다).
 */
const SCREENSHOT_DIR = 'playwright-report/track-106-3'
const DESKTOP_VIEWPORT = { width: 1440, height: 900 }
const MOBILE_VIEWPORT = { width: 390, height: 844 }
const PRODUCT_ID = 'prd_E2E00000000000000000001063'

const FAQS = [
  { id: 9001, category: 'ORDER_PAYMENT', question: '결제 수단은 어떻게 고르나요?', answer: '주문/결제 화면의 결제 수단에서 고르세요.' },
  { id: 9002, category: 'DELIVERY', question: '송장번호는 어디서 볼 수 있나요?', answer: '주문 상세의 상품별 배송 정보에서 확인할 수 있습니다.' },
  { id: 9003, category: 'DELIVERY', question: '구매확정은 무엇인가요?', answer: '상품을 잘 받았음을 확정하는 절차입니다.' },
  { id: 9004, category: 'CLAIM', question: '반품은 언제까지 요청할 수 있나요?', answer: '배송완료 후 7일 이내에 요청할 수 있습니다.' },
  { id: 9005, category: 'ACCOUNT', question: '비밀번호를 바꾸고 싶어요.', answer: '마이페이지의 비밀번호 변경에서 바꿀 수 있습니다.' },
  { id: 9006, category: 'REVIEW_QUESTION', question: '리뷰는 언제 쓸 수 있나요?', answer: '구매확정한 상품에 한해 작성할 수 있습니다.' },
]

const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 도우미 셔츠', description: Array.from({ length: 40 }, (_, index) => `설명 ${index + 1}줄`).join('\n'),
  categoryId: 3, categoryName: '의류', sellerName: 'E2E셀러', sellerPublicId: 'slr_E2E00000000000000000001061', displayPrice: 29000,
  soldOut: false, saleStopped: false, images: [], optionGroups: [], variants: [{ variantPublicId: 'var_E2E8', salePrice: 29000, soldOut: false, options: [] }],
}
const EMPTY_PAGE = { items: [], page: 0, size: 10, totalCount: 0, hasNext: false }
const EMPTY_REVIEW_SUMMARY = { reviewCount: 0, averageRating: 0, ratingDistribution: [], keywords: [], recentPhotos: [] }

async function mockFaqApis(page: Page): Promise<void> {
  await page.route((url) => url.pathname === '/api/v1/faqs', (route) => route.fulfill({ json: FAQS }))
  await page.route((url) => url.pathname === '/api/v1/faqs/suggest', (route) => {
    const query = new URL(route.request().url()).searchParams.get('q') ?? ''
    return route.fulfill({ json: query.includes('송장') ? [FAQS[1]] : [] })
  })
}

async function mockProductApis(page: Page): Promise<void> {
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}`, (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({ json: { ...EMPTY_PAGE, size: 6 } }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/summary`, (route) => route.fulfill({ json: EMPTY_REVIEW_SUMMARY }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: [] }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews`, (route) => route.fulfill({ json: EMPTY_PAGE }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions`, (route) => route.fulfill({ json: EMPTY_PAGE }))
}

async function openAssistant(page: Page): Promise<void> {
  await page.getByTestId('faq-launcher').click()
  await expect(page.getByTestId('faq-panel')).toBeVisible()
  await expect(page.getByTestId('faq-category-chip')).toHaveCount(5)
}

function lastBubble(page: Page) {
  return page.getByTestId('faq-bubble').last()
}

test.describe('구매자 채팅 도우미(Track 106-3)', () => {
  test('① 카테고리 → 질문 → 답 · 다른 페이지 이동과 새로고침 뒤에도 대화 유지 · Esc 닫힘', async ({ page }) => {
    await mockFaqApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await page.goto('/')
    await expect(page.getByTestId('faq-launcher')).toBeVisible()

    await openAssistant(page)
    await expect(page.getByTestId('faq-bubble').first()).toContainText('무엇을 도와드릴까요')
    await page.getByTestId('faq-category-chip').filter({ hasText: '배송' }).click()
    await expect(lastBubble(page)).toContainText('배송 관련 자주 묻는 질문이에요.')
    await expect(page.getByTestId('faq-chip')).toHaveText(['송장번호는 어디서 볼 수 있나요?', '구매확정은 무엇인가요?'])

    await page.getByTestId('faq-chip').filter({ hasText: '송장번호' }).click()
    await expect(lastBubble(page)).toHaveText('주문 상세의 상품별 배송 정보에서 확인할 수 있습니다.')
    await expect(page.getByTestId('faq-message').nth(-2)).toHaveAttribute('data-role', 'user')
    await expect(page.getByTestId('faq-chip')).toHaveText(['배송 다른 질문', '처음으로'])
    await page.getByTestId('faq-chip').filter({ hasText: '배송 다른 질문' }).click()
    await expect(page.getByTestId('faq-chip')).toHaveText(['구매확정은 무엇인가요?'])
    const messageCount = await page.getByTestId('faq-message').count()

    await gotoClientSide(page, '/products')
    await expect(page.getByTestId('faq-panel')).toBeVisible()
    await expect(page.getByTestId('faq-message')).toHaveCount(messageCount)
    await page.reload()
    await expect(page.getByTestId('faq-panel')).toBeVisible()
    await expect(page.getByTestId('faq-message')).toHaveCount(messageCount)

    await page.getByTestId('faq-input').focus()
    await page.keyboard.press('Escape')
    await expect(page.getByTestId('faq-panel')).toHaveCount(0)
    await expect(page.getByTestId('faq-launcher')).toBeFocused()
  })

  test('② 자유 입력: 입력 중 미리보기 · 전송 결과 칩 → 답 · 0건이면 찾지 못했어요 + 카테고리 칩', async ({ page }) => {
    await mockFaqApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await page.goto('/')
    await openAssistant(page)

    await page.getByTestId('faq-input').fill('송장 어디서')
    await expect(page.getByTestId('faq-live-suggestion')).toHaveText(['송장번호는 어디서 볼 수 있나요?'])
    await page.getByTestId('faq-input').press('Enter')
    await expect(page.getByTestId('faq-message').nth(-2)).toContainText('송장 어디서')
    await expect(lastBubble(page)).toHaveText('이런 질문을 찾았어요.')
    await page.getByTestId('faq-chip').filter({ hasText: '송장번호' }).click()
    await expect(lastBubble(page)).toHaveText('주문 상세의 상품별 배송 정보에서 확인할 수 있습니다.')

    await page.getByTestId('faq-input').fill('환율 문의')
    await page.getByTestId('faq-send').click()
    await expect(lastBubble(page)).toContainText('찾지 못했어요')
    await expect(page.getByTestId('faq-category-chip')).toHaveCount(5)
  })

  test('③ 상품 상세 첫 칩 강조 · 390px 하단 고정 바가 보이면 버튼이 바 위로', async ({ page }) => {
    await mockFaqApis(page)
    await mockProductApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await openAssistant(page)
    await expect(page.getByTestId('faq-category-chip')).toHaveText(['배송', '취소·반품·교환', '주문·결제', '회원·계정', '리뷰·문의'])
    await expect(page.locator('[data-testid="faq-category-chip"][data-emphasized="true"]')).toHaveText(['배송', '취소·반품·교환'])
    await page.getByTestId('faq-close').click()

    await page.setViewportSize(MOBILE_VIEWPORT)
    await expect(page.getByText(PRODUCT_DETAIL.name).first()).toBeVisible()
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    const launcher = page.getByTestId('faq-launcher')
    await expect(page.locator('body')).toHaveAttribute('data-mobile-action-bar', 'shown')
    const bar = page.locator('div.fixed.inset-x-0.bottom-0.z-40')
    await expect(bar).toBeVisible()
    await expect.poll(async () => {
      const launcherBottom = await launcher.evaluate((element) => element.getBoundingClientRect().bottom)
      const barTop = await bar.evaluate((element) => element.getBoundingClientRect().top)
      return launcherBottom <= barTop
    }).toBe(true)
  })

  test('④ 제외 경로(모의 결제)에는 도우미 버튼이 없다 — 홈에는 있다', async ({ page }) => {
    await loginAs(page, 'BUYER')
    await page.goto('/')
    await expect(page.getByTestId('faq-launcher')).toBeVisible()
    await page.goto('/payment/mock')
    await expect(page.getByTestId('payment-mock-title')).toBeVisible()
    await expect(page.getByTestId('faq-launcher')).toHaveCount(0)
  })

  test('스크린샷: 데스크톱·390px — 닫힘 · 인사 · 답 · 자유 입력 결과 · 0건 · 상품 상세 하단 바 위', async ({ page }) => {
    await mockFaqApis(page)
    await mockProductApis(page)
    for (const [label, viewport] of [['desktop', DESKTOP_VIEWPORT], ['mobile', MOBILE_VIEWPORT]] as const) {
      await page.setViewportSize(viewport)
      await page.goto('/')
      await page.evaluate(() => window.sessionStorage.clear())
      await page.reload()
      await expect(page.getByTestId('faq-launcher')).toBeVisible()
      await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-closed-${label}.png`, animations: 'disabled' })

      await openAssistant(page)
      await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-greeting-${label}.png`, animations: 'disabled' })
      await page.getByTestId('faq-category-chip').filter({ hasText: '배송' }).click()
      await page.getByTestId('faq-chip').filter({ hasText: '송장번호' }).click()
      await expect(lastBubble(page)).toHaveAttribute('data-answer', 'true')
      await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-answer-${label}.png`, animations: 'disabled' })

      await page.getByTestId('faq-input').fill('송장 어디서')
      await expect(page.getByTestId('faq-live-suggestion')).toHaveCount(1)
      await page.getByTestId('faq-input').press('Enter')
      await expect(lastBubble(page)).toHaveText('이런 질문을 찾았어요.')
      await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-search-${label}.png`, animations: 'disabled' })

      await page.getByTestId('faq-input').fill('환율 문의')
      await page.getByTestId('faq-input').press('Enter')
      await expect(lastBubble(page)).toContainText('찾지 못했어요')
      await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-not-found-${label}.png`, animations: 'disabled' })
      await page.getByTestId('faq-close').click()
    }

    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    await expect(page.getByText(PRODUCT_DETAIL.name).first()).toBeVisible()
    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    await expect(page.locator('body')).toHaveAttribute('data-mobile-action-bar', 'shown')
    // 버튼 bottom은 전환(transition)으로 움직이므로 끝날 때까지 기다린다.
    await expect.poll(async () => {
      const launcherBottom = await page.getByTestId('faq-launcher').evaluate((element) => element.getBoundingClientRect().bottom)
      const barTop = await page.locator('div.fixed.inset-x-0.bottom-0.z-40').evaluate((element) => element.getBoundingClientRect().top)
      return launcherBottom <= barTop
    }).toBe(true)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-above-action-bar-mobile.png`, animations: 'disabled' })
  })
})
