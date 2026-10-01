import { test, expect, type Page } from './fixtures'
import { loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'
import { mockSellerMe } from './helpers/seller-mock'

/**
 * 상품 Q&A(Track 106-2) E2E. 질문·리뷰·상품 API는 page.route로 mock해 로컬 DB를 바꾸지 않는다(reviews.spec 패턴). 로그인만 실 BE(loginAs).
 * 구매자 SSR 페이지는 gotoClientSide로 들어가 조회가 브라우저에서 실행되게 한다. 관리자·셀러 레이어는 ssr:false라 page.goto로 충분하다.
 * ① 상품 상세: 입력 → 즉시 답 카드(유형 라벨) → 셀러에게 질문 남기기 → POST 본문 → 목록 반영("답변 대기")
 * ② 셀러: 미답변 목록(기본 필터) → 답변 다이얼로그 → PUT 본문 → 목록 재조회
 * ③ 관리자: 숨김 다이얼로그 사유 필수 → PATCH {status, reason} → 목록 재조회
 */
const PRODUCT_ID = 'prd_E2E00000000000000000001062'
const QUESTION_ID = 'pqn_E2E00000000000000000001061'
const CREATED_QUESTION = 'pqn_E2E00000000000000000001069'

const PRODUCT_DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 면 티셔츠', description: '면 100% 소재입니다.\n세탁기 사용이 가능합니다.', categoryId: 3, categoryName: '의류',
  sellerName: 'E2E셀러', sellerPublicId: 'slr_E2E00000000000000000001061', displayPrice: 19000, soldOut: false, saleStopped: false, images: [],
  optionGroups: [], variants: [{ variantPublicId: 'var_E2E9', salePrice: 19000, soldOut: false, options: [] }],
}
const EMPTY_REVIEW_SUMMARY = { reviewCount: 0, ratingDistribution: [5, 4, 3, 2, 1].map((rating) => ({ rating, count: 0 })), keywords: [], recentPhotos: [] }
const EMPTY_PAGE = { items: [], page: 0, size: 10, totalCount: 0, hasNext: false }
const SUGGESTIONS = [
  { type: 'PRODUCT', text: '세탁기 사용이 가능합니다.' },
  { type: 'REVIEW', text: '세탁해도 줄어들지 않아요', id: 'rvw_E2E00000000000000000001061' },
  { type: 'QNA', text: '세탁 방법이 궁금합니다', answer: '찬물 단독 세탁을 권합니다', id: QUESTION_ID },
]

interface BuyerCaptured {
  suggestQueries: string[]
  created: unknown[]
}

async function mockBuyerApis(page: Page): Promise<BuyerCaptured> {
  const captured: BuyerCaptured = { suggestQueries: [], created: [] }
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}`, (route) => route.fulfill({ json: PRODUCT_DETAIL }))
  await page.route((url) => url.pathname === '/api/v1/products', (route) => route.fulfill({ json: { ...EMPTY_PAGE, size: 6 } }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/summary`, (route) => route.fulfill({ json: EMPTY_REVIEW_SUMMARY }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews/keywords`, (route) => route.fulfill({ json: [] }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/reviews`, (route) => route.fulfill({ json: EMPTY_PAGE }))
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions/suggest`, (route) => {
    captured.suggestQueries.push(new URL(route.request().url()).searchParams.get('q') ?? '')
    return route.fulfill({ json: SUGGESTIONS })
  })
  await page.route((url) => url.pathname === `/api/v1/products/${PRODUCT_ID}/questions`, (route) => route.fulfill({
    json: captured.created.length === 0
      ? EMPTY_PAGE
      : {
          items: [{ questionId: CREATED_QUESTION, content: '세탁기 건조도 되나요?', writtenByMe: true, createdAt: '2026-09-30T12:00:00.000+09:00' }],
          page: 0, size: 10, totalCount: 1, hasNext: false,
        },
  }))
  await page.route((url) => url.pathname === '/api/v1/product-questions', (route) => {
    captured.created.push(JSON.parse(route.request().postData() ?? '{}'))
    return route.fulfill({ status: 201, json: { questionId: CREATED_QUESTION } })
  })
  return captured
}

test.describe('상품 Q&A(Track 106-2)', () => {
  test('① 상품 상세: 입력 → 즉시 답 카드 → 셀러에게 질문 남기기 → POST 본문 → 목록 "답변 대기"', async ({ page }) => {
    const captured = await mockBuyerApis(page)
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/products/${PRODUCT_ID}`)
    const section = page.getByTestId('product-questions')
    await expect(section.getByTestId('product-questions-empty')).toContainText('첫 질문을 남겨 보세요')

    await section.getByTestId('product-question-input').fill('세탁기 건조도 되나요?')
    const cards = section.getByTestId('product-question-suggestion')
    await expect(cards).toHaveCount(3)
    await expect(cards.getByTestId('suggestion-type')).toHaveText(['상품 설명', '리뷰', 'Q&A'])
    await expect(cards.nth(2).getByTestId('suggestion-answer')).toContainText('찬물 단독 세탁을 권합니다')
    expect(captured.suggestQueries.at(-1)).toBe('세탁기 건조도 되나요?')

    await section.getByTestId('product-question-submit').click()
    await expect(section.getByTestId('product-question-notice')).toContainText('질문을 남겼어요')
    expect(captured.created).toEqual([{ productId: PRODUCT_ID, content: '세탁기 건조도 되나요?' }])
    await expect(section.getByTestId('product-question-input')).toHaveValue('')
    await expect(section.getByTestId('product-question-suggestion')).toHaveCount(0)
    const item = section.getByTestId('product-question-item')
    await expect(item).toHaveCount(1)
    await expect(item.getByTestId('question-waiting')).toHaveText('답변 대기')
    await expect(item.getByTestId('question-actions')).toBeVisible()
  })

  test('② 셀러: 미답변 목록(기본 필터) → 답변 다이얼로그 → PUT {content} → 목록 재조회', async ({ page }) => {
    const listCalls: URL[] = []
    const answers: { url: string; body: unknown }[] = []
    await mockSellerMe(page)
    await page.route((url) => url.pathname === '/api/v1/seller/product-questions', (route) => {
      listCalls.push(new URL(route.request().url()))
      return route.fulfill({
        json: {
          items: [{ questionId: QUESTION_ID, productPublicId: PRODUCT_ID, productName: 'E2E 면 티셔츠', content: '세탁 방법이 궁금합니다', createdAt: '2026-09-25T10:00:00.000+09:00' }],
          page: 0, size: 20, totalCount: 1, hasNext: false,
        },
      })
    })
    await page.route((url) => /\/api\/v1\/seller\/product-questions\/pqn_[^/]+\/answer$/.test(url.pathname), (route) => {
      answers.push({ url: route.request().url(), body: JSON.parse(route.request().postData() ?? '{}') })
      return route.fulfill({ status: 204, body: '' })
    })
    await loginAs(page, 'SELLER')
    await page.goto('/seller/products/questions')

    const table = page.getByTestId('seller-question-table')
    await expect(table.getByTestId('row-question')).toHaveText(['세탁 방법이 궁금합니다'])
    await expect(table.getByTestId('row-unanswered')).toHaveText(['미답변'])
    await expect(table.getByTestId('row-elapsed')).toHaveCount(1)
    expect(listCalls.at(-1)!.searchParams.get('answered')).toBe('UNANSWERED')

    await table.getByTestId('row-answer-open').click()
    const dialog = page.getByTestId('seller-question-answer-dialog')
    await expect(dialog.getByTestId('answer-dialog-ok')).toBeDisabled()
    await dialog.locator('textarea').first().fill('  찬물 단독 세탁을 권합니다  ')
    const callsBefore = listCalls.length
    await dialog.getByTestId('answer-dialog-ok').click()

    await expect(dialog).toHaveCount(0)
    expect(answers).toEqual([{ url: expect.stringContaining(`/api/v1/seller/product-questions/${QUESTION_ID}/answer`), body: { content: '찬물 단독 세탁을 권합니다' } }])
    await expect.poll(() => listCalls.length).toBeGreaterThan(callsBefore)
  })

  test('③ 관리자: 목록 표시 · 숨김 모달 사유 필수 → PATCH {status, reason} → 목록 재조회', async ({ page }) => {
    const listCalls: string[] = []
    const patches: { url: string; body: unknown }[] = []
    await page.route((url) => url.pathname === '/api/v1/admin/product-questions', (route) => {
      listCalls.push(route.request().url())
      return route.fulfill({
        json: {
          items: [
            { questionId: QUESTION_ID, productPublicId: PRODUCT_ID, productName: 'E2E 면 티셔츠', content: '지금 이 링크로 오세요 — 광고 문구 예시', status: 'VISIBLE', createdAt: '2026-09-29T10:00:00.000+09:00' },
            { questionId: CREATED_QUESTION, content: '숨겨진 질문', answerContent: '답변', answeredAt: '2026-09-28T11:00:00.000+09:00', status: 'HIDDEN', hiddenReason: '욕설 포함', createdAt: '2026-09-28T10:00:00.000+09:00' },
          ],
          page: 0, size: 20, totalCount: 2, hasNext: false,
        },
      })
    })
    await page.route((url) => /\/api\/v1\/admin\/product-questions\/pqn_[^/]+\/status$/.test(url.pathname), (route) => {
      patches.push({ url: route.request().url(), body: JSON.parse(route.request().postData() ?? '{}') })
      return route.fulfill({ status: 204, body: '' })
    })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/questions')

    const table = page.getByTestId('admin-question-table')
    await expect(table.getByTestId('row-product')).toHaveText(['E2E 면 티셔츠', '삭제된 상품'])
    await expect(table.getByTestId('row-answered')).toHaveText(['미답변', '답변완료'])
    await expect(table.getByTestId('row-status')).toHaveText(['공개', '숨김'])
    await expect(table.getByTestId('row-hidden-reason')).toHaveText(['—', '욕설 포함'])
    await expect(table.getByTestId('row-change-status')).toHaveText(['숨김', '숨김 해제'])

    await table.getByTestId('row-change-status').first().click()
    const dialog = page.getByTestId('admin-question-status-dialog')
    await expect(dialog).toContainText('질문 숨김')
    await expect(dialog.getByTestId('question-status-ok')).toBeDisabled()
    await dialog.getByRole('textbox', { name: '사유 (필수)' }).fill('  광고성 질문  ')
    const callsBefore = listCalls.length
    await dialog.getByTestId('question-status-ok').click()

    await expect(dialog).toHaveCount(0)
    expect(patches).toEqual([{ url: expect.stringContaining(`/api/v1/admin/product-questions/${QUESTION_ID}/status`), body: { status: 'HIDDEN', reason: '광고성 질문' } }])
    await expect.poll(() => listCalls.length).toBeGreaterThan(callsBefore)
  })
})
