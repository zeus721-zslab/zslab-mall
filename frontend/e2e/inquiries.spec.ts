import { test, expect, type Page } from '@playwright/test'
import { hasE2eCredentials, loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'

/**
 * 운영자 문의(Track 106-4) E2E. 문의 API는 page.route로 mock해 로컬 DB를 바꾸지 않는다(product-questions.spec 패턴) — 로그인과 ③의 주문 조회만 실 BE.
 * 구매자 SSR 페이지는 gotoClientSide로 들어가 조회가 브라우저에서 실행되게 한다. 관리자 레이어는 ssr:false라 page.goto로 충분하다.
 * ① 작성: 도우미 숨김 · 취소·반품·교환 안내(주문 없음 → 주문 내역 / 주문 선택 → 주문 상세) · 주문 미선택이면 orderId 없는 POST → 내 문의에서 새 문의 펼침
 * ② 비로그인: 도우미 0건 → 운영자에게 남기기 → 로그인 → 작성 화면 복귀
 * ③ 주문 상세에서 연 도우미 → 작성 화면에 그 주문이 선택돼 있음(실 주문 · 없으면 skip)
 * ④ 내 문의: 새 답변 표시 → 펼치면 answer-check 1회 · 표시 해제 · 답변 달린 문의는 수정·삭제 없음 / 미답변은 있음
 * ⑤ 관리자: 기본 미답변 목록 · 마스킹 작성자 · 답변 다이얼로그 → PUT {content} → 목록 재조회
 * 스크린샷 → playwright-report/track-106-4(docs/frontend/screens-track-106-4로 옮긴다).
 */
const SCREENSHOT_DIR = 'playwright-report/track-106-4'
const DESKTOP_VIEWPORT = { width: 1440, height: 900 }
const MOBILE_VIEWPORT = { width: 390, height: 844 }
const ORDER_ID = 'ord_01KXE2E0000000000000000106'
const CREATED_ID = 'inq_01KXE2E0000000000000000106'
const ANSWERED_ID = 'inq_01KXE2E0000000000000000107'
const OPEN_ID = 'inq_01KXE2E0000000000000000108'

const ORDER_PAGE = {
  items: [{
    orderId: ORDER_ID, orderNo: 'ORD-20260928-0106', previewTitle: 'E2E 면 티셔츠 외 1건', sellerCount: 1, totalPrice: 38000,
    status: { code: 'DELIVERED', label: '배송완료' }, orderedAt: '2026-09-28T10:00:00.000+09:00', activeClaims: [],
  }],
  page: 0, size: 10, totalCount: 1, hasNext: false,
}
const FAQS = [{ id: 9101, category: 'DELIVERY', question: '송장번호는 어디서 볼 수 있나요?', answer: '주문 상세에서 확인할 수 있습니다.' }]

function page1<T>(items: T[]) {
  return { items, page: 0, size: 10, totalCount: items.length, hasNext: false }
}

async function mockFaqApis(page: Page): Promise<void> {
  await page.route((url) => url.pathname === '/api/v1/faqs', (route) => route.fulfill({ json: FAQS }))
  await page.route((url) => url.pathname === '/api/v1/faqs/suggest', (route) => route.fulfill({ json: [] }))
}

/** 도우미를 열고 자유 입력 0건까지 진행한다(마지막 말풍선 칩 = 운영자에게 남기기). */
async function searchWithoutResult(page: Page): Promise<void> {
  await page.getByTestId('faq-launcher').click()
  await expect(page.getByTestId('faq-panel')).toBeVisible()
  await page.getByTestId('faq-input').fill('환율 문의')
  await page.getByTestId('faq-send').click()
  await expect(page.getByTestId('faq-bubble').last()).toContainText('찾지 못했어요')
  await expect(page.getByTestId('faq-chip')).toHaveText(['운영자에게 남기기'])
}

test.describe('운영자 문의(Track 106-4)', () => {
  test('① 작성: 도우미 숨김 · 취소·반품·교환 안내 · 주문 미선택 POST(orderId 없음) → 내 문의에서 새 문의 펼침', async ({ page }) => {
    const created: unknown[] = []
    await page.route((url) => url.pathname === '/api/v1/orders', (route) => route.fulfill({ json: ORDER_PAGE }))
    await page.route((url) => url.pathname === '/api/v1/inquiries', (route) => {
      created.push(JSON.parse(route.request().postData() ?? '{}'))
      return route.fulfill({ status: 201, json: { inquiryId: CREATED_ID } })
    })
    await page.route((url) => url.pathname === '/api/v1/inquiries/me', (route) => route.fulfill({
      json: page1(created.length === 0 ? [] : [{
        inquiryId: CREATED_ID, category: 'CLAIM', content: '반품 절차가 궁금해요', editable: true, deletable: true, unread: false,
        createdAt: '2026-09-30T12:00:00.000+09:00',
      }]),
    }))
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, '/mypage/inquiries/new')

    await expect(page.getByTestId('inquiry-new-form')).toBeVisible()
    await expect(page.getByTestId('faq-launcher')).toHaveCount(0)
    await expect(page.getByTestId('inquiry-order').locator('option')).toHaveText(['선택 안 함', 'ORD-20260928-0106 · E2E 면 티셔츠 외 1건 · 2026.09.28'])

    await page.getByTestId('inquiry-category').filter({ hasText: '취소·반품·교환' }).click()
    await expect(page.getByTestId('inquiry-claim-guide')).toContainText('주문 상세에서 바로 신청할 수 있어요')
    await expect(page.getByTestId('inquiry-claim-guide-link')).toHaveAttribute('href', '/orders')
    await page.getByTestId('inquiry-order').selectOption(ORDER_ID)
    await expect(page.getByTestId('inquiry-claim-guide-link')).toHaveAttribute('href', `/orders/${ORDER_ID}`)
    await page.getByTestId('inquiry-content').fill('반품 절차가 궁금해요')
    await expect(page.getByTestId('inquiry-content-count')).toHaveText('11/500')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/inquiry-new-desktop.png`, animations: 'disabled' })

    await page.getByTestId('inquiry-order').selectOption('')
    await page.getByTestId('inquiry-submit').click()
    await page.waitForURL((url) => url.pathname === '/mypage/inquiries' && url.searchParams.get('open') === CREATED_ID)
    expect(created).toEqual([{ category: 'CLAIM', content: '반품 절차가 궁금해요' }])
    const item = page.getByTestId('my-inquiry-item')
    await expect(item).toHaveCount(1)
    await expect(item.getByTestId('my-inquiry-detail')).toBeVisible()
    await expect(item.getByTestId('my-inquiry-content')).toHaveText('반품 절차가 궁금해요')
    await expect(item.getByTestId('my-inquiry-waiting')).toHaveText('답변 대기')
  })

  test('② 비로그인: 도우미 0건 → 운영자에게 남기기 → 로그인 → 작성 화면 복귀', async ({ page }) => {
    test.skip(!hasE2eCredentials('BUYER'), 'BUYER_E2E_EMAIL / BUYER_E2E_PASSWORD 미설정')
    await mockFaqApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await page.goto('/')
    await searchWithoutResult(page)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-inquiry-chip-desktop.png`, animations: 'disabled' })

    await page.getByTestId('faq-chip').filter({ hasText: '운영자에게 남기기' }).click()
    await page.waitForURL((url) => url.pathname === '/login' && url.searchParams.get('redirect') === '/mypage/inquiries/new')
    await page.getByTestId('login-email').fill(process.env.BUYER_E2E_EMAIL ?? '')
    await page.getByTestId('login-password').fill(process.env.BUYER_E2E_PASSWORD ?? '')
    await page.getByTestId('login-submit').click()
    await page.waitForURL((url) => url.pathname === '/mypage/inquiries/new')
    await expect(page.getByTestId('inquiry-new-form')).toBeVisible()
  })

  test('③ 주문 상세에서 연 도우미 → 작성 화면에 그 주문이 선택돼 있음', async ({ page }) => {
    await mockFaqApis(page)
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await loginAs(page, 'BUYER')
    const orders = await page.request.get('/api/v1/orders?page=0&size=1')
    expect(orders.ok()).toBe(true)
    const orderId = ((await orders.json()) as { items: { orderId: string }[] }).items[0]?.orderId
    test.skip(orderId === undefined, '데모 구매자 주문 없음')

    await gotoClientSide(page, `/orders/${orderId}`)
    await searchWithoutResult(page)
    await page.getByTestId('faq-chip').filter({ hasText: '운영자에게 남기기' }).click()
    await page.waitForURL((url) => url.pathname === '/mypage/inquiries/new' && url.searchParams.get('order') === orderId)
    await expect(page.getByTestId('inquiry-order')).toHaveValue(orderId!)
  })

  test('④ 내 문의: 새 답변 → 펼치면 answer-check 1회·표시 해제 · 답변 달린 문의는 수정·삭제 없음', async ({ page }) => {
    const checks: string[] = []
    await page.route((url) => url.pathname === '/api/v1/inquiries/me', (route) => route.fulfill({
      json: page1([
        {
          inquiryId: ANSWERED_ID, category: 'DELIVERY', content: '배송지를 바꾸고 싶어요', orderId: ORDER_ID, orderNo: 'ORD-20260928-0106',
          answerContent: '배송 전이라 변경해 드렸습니다.', answeredAt: '2026-09-30T13:00:00.000+09:00', editable: false, deletable: false, unread: true,
          createdAt: '2026-09-30T12:00:00.000+09:00',
        },
        { inquiryId: OPEN_ID, category: 'OTHER', content: '기타 문의입니다', editable: true, deletable: true, unread: false, createdAt: '2026-09-29T12:00:00.000+09:00' },
      ]),
    }))
    await page.route((url) => url.pathname.endsWith('/answer-check'), (route) => {
      checks.push(new URL(route.request().url()).pathname)
      return route.fulfill({ status: 204, body: '' })
    })
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, '/mypage/inquiries')

    const items = page.getByTestId('my-inquiry-item')
    await expect(items).toHaveCount(2)
    await expect(page.getByTestId('my-inquiry-unread')).toHaveCount(1)
    await expect(page.getByTestId('my-inquiry-new')).toHaveAttribute('href', '/mypage/inquiries/new')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/my-inquiries-desktop.png`, animations: 'disabled' })

    const answered = items.nth(0)
    await answered.getByTestId('my-inquiry-toggle').click()
    await expect(answered.getByTestId('my-inquiry-answer')).toContainText('배송 전이라 변경해 드렸습니다.')
    await expect(answered.getByTestId('my-inquiry-order')).toContainText('ORD-20260928-0106')
    await expect(page.getByTestId('my-inquiry-unread')).toHaveCount(0)
    expect(checks).toEqual([`/api/v1/inquiries/${ANSWERED_ID}/answer-check`])
    await expect(answered.getByTestId('my-inquiry-edit')).toHaveCount(0)
    await expect(answered.getByTestId('my-inquiry-delete')).toHaveCount(0)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/my-inquiries-expanded-desktop.png`, animations: 'disabled' })

    await answered.getByTestId('my-inquiry-toggle').click()
    await answered.getByTestId('my-inquiry-toggle').click()
    expect(checks).toHaveLength(1)

    const open = items.nth(1)
    await open.getByTestId('my-inquiry-toggle').click()
    await expect(open.getByTestId('my-inquiry-edit')).toBeVisible()
    await expect(open.getByTestId('my-inquiry-delete')).toBeVisible()
  })

  test('⑤ 관리자: 기본 미답변 목록 · 마스킹 작성자 · 답변 다이얼로그 → PUT {content} → 목록 재조회', async ({ page }) => {
    const listCalls: URL[] = []
    const answers: { url: string; body: unknown }[] = []
    await page.route((url) => url.pathname === '/api/v1/admin/inquiries', (route) => {
      listCalls.push(new URL(route.request().url()))
      return route.fulfill({
        json: {
          items: [
            { inquiryId: OPEN_ID, category: 'CLAIM', content: '반품 절차가 궁금해요', orderId: ORDER_ID, orderNo: 'ORD-20260928-0106', buyerEmailMasked: 'bu***@demo.test', createdAt: '2026-09-29T10:00:00.000+09:00' },
            { inquiryId: ANSWERED_ID, category: 'OTHER', content: '기타 문의입니다', answerContent: '확인했습니다', answeredAt: '2026-09-29T12:00:00.000+09:00', createdAt: '2026-09-29T11:00:00.000+09:00' },
          ],
          page: 0, size: 20, totalCount: 2, hasNext: false,
        },
      })
    })
    await page.route((url) => /\/api\/v1\/admin\/inquiries\/inq_[^/]+\/answer$/.test(url.pathname), (route) => {
      answers.push({ url: route.request().url(), body: JSON.parse(route.request().postData() ?? '{}') })
      return route.fulfill({ status: 204, body: '' })
    })
    await page.setViewportSize(DESKTOP_VIEWPORT)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/inquiries')

    const table = page.getByTestId('admin-inquiry-table')
    await expect(table.getByTestId('row-category')).toHaveText(['취소·반품·교환', '기타'])
    await expect(table.getByTestId('row-buyer')).toHaveText(['bu***@demo.test', '—'])
    await expect(table.getByTestId('row-order')).toHaveText(['ORD-20260928-0106'])
    await expect(table.getByTestId('row-answered')).toHaveText(['미답변', '답변완료'])
    await expect(table.getByTestId('row-answer')).toHaveText(['답변하기', '답변 수정'])
    expect(listCalls[0]!.searchParams.get('answered')).toBe('UNANSWERED')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/admin-inquiries-desktop.png`, animations: 'disabled' })

    await table.getByTestId('row-answer').first().click()
    const dialog = page.getByTestId('admin-inquiry-answer-dialog')
    await expect(dialog.getByTestId('answer-inquiry')).toContainText('반품 절차가 궁금해요')
    await expect(dialog.getByTestId('answer-dialog-ok')).toBeDisabled()
    await dialog.getByRole('textbox', { name: '답변' }).fill('  주문 상세 > 반품 요청에서 신청해 주세요.  ')
    await page.screenshot({ path: `${SCREENSHOT_DIR}/admin-inquiry-dialog-desktop.png`, animations: 'disabled' })
    const callsBefore = listCalls.length
    await dialog.getByTestId('answer-dialog-ok').click()

    await expect(dialog).toHaveCount(0)
    expect(answers).toEqual([{ url: expect.stringContaining(`/api/v1/admin/inquiries/${OPEN_ID}/answer`), body: { content: '주문 상세 > 반품 요청에서 신청해 주세요.' } }])
    await expect.poll(() => listCalls.length).toBeGreaterThan(callsBefore)
  })

  test('⑥ 390px: 작성 화면 · 내 문의 · 도우미 운영자에게 남기기 칩', async ({ page }) => {
    await mockFaqApis(page)
    await page.route((url) => url.pathname === '/api/v1/orders', (route) => route.fulfill({ json: ORDER_PAGE }))
    await page.route((url) => url.pathname === '/api/v1/inquiries/me', (route) => route.fulfill({
      json: page1([{
        inquiryId: ANSWERED_ID, category: 'DELIVERY', content: '배송지를 바꾸고 싶어요', answerContent: '배송 전이라 변경해 드렸습니다.',
        answeredAt: '2026-09-30T13:00:00.000+09:00', editable: false, deletable: false, unread: true, createdAt: '2026-09-30T12:00:00.000+09:00',
      }]),
    }))
    await page.setViewportSize(MOBILE_VIEWPORT)
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, '/mypage/inquiries/new')
    await page.getByTestId('inquiry-category').filter({ hasText: '취소·반품·교환' }).click()
    await expect(page.getByTestId('inquiry-claim-guide')).toBeVisible()
    await page.screenshot({ path: `${SCREENSHOT_DIR}/inquiry-new-mobile.png`, animations: 'disabled', fullPage: true })

    await gotoClientSide(page, '/mypage/inquiries')
    await expect(page.getByTestId('my-inquiry-unread')).toHaveCount(1)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/my-inquiries-mobile.png`, animations: 'disabled' })

    await searchWithoutResult(page)
    await page.screenshot({ path: `${SCREENSHOT_DIR}/assistant-inquiry-chip-mobile.png`, animations: 'disabled' })
  })
})
