import { test, expect } from '@playwright/test'
import { loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'

/**
 * mock 결제 페이지 E2E(Track 93 D-198). 로그인은 공용 헬퍼 loginAs(BUYER_E2E_* 주입·미주입 시 skip)이며 gotoClientSide로 클라이언트 내비게이션해
 * 이후 $fetch가 브라우저에서 실행되도록 한다. mock 콜백 API는 page.route로 가로채 로컬 DB를 바꾸지 않고 요청 계약(경로·Bearer·body)만 검증한다.
 */
const ATTEMPT_KEY = 'pat_E2E00000000000000000000931'
const ORDER_ID = 'ord_E2E00000000000000000000931'
const MOCK_PAGE = `/payment/mock?attemptKey=${ATTEMPT_KEY}&amount=19900&method=CARD&orderPublicId=${ORDER_ID}`

interface CapturedCallback {
  method: string
  authorization: string
  body: Record<string, unknown>
}

test.describe('mock 결제 페이지(Track 93 인가 endpoint)', () => {
  test('① 결제 성공 → POST /api/v1/payments/mock-callback(Bearer·attemptKey·callbackType만) → /checkout/complete 이동·주문번호 표시 · webhook 경로 미호출', async ({ page }) => {
    const captured: CapturedCallback[] = []
    let webhookCalls = 0

    await page.route((url) => url.pathname.endsWith('/api/webhooks/payments'), (route) => {
      webhookCalls += 1
      return route.fulfill({ status: 404, body: '' })
    })
    await page.route((url) => url.pathname.endsWith('/api/v1/payments/mock-callback'), (route) => {
      captured.push({
        method: route.request().method(),
        authorization: route.request().headers().authorization ?? '',
        body: route.request().postDataJSON() as Record<string, unknown>,
      })
      return route.fulfill({ status: 200, body: '' })
    })
    // 완료 화면 진입 시 카트 재조회는 실 API 대신 빈 카트로 응답(로컬 DB 무변경).
    await page.route((url) => url.pathname.endsWith('/api/v1/cart'), (route) => route.fulfill({ json: { items: [], totalQuantity: 0 } }))

    await loginAs(page, 'BUYER')
    await gotoClientSide(page, MOCK_PAGE)

    await expect(page.getByTestId('payment-mock-title')).toBeVisible()
    await page.getByTestId('payment-mock-success').click()

    await page.waitForURL((url) => url.pathname === '/checkout/complete')
    expect(new URL(page.url()).searchParams.get('orderPublicId')).toBe(ORDER_ID)
    await expect(page.getByTestId('checkout-complete-order-id')).toHaveText(ORDER_ID)

    expect(webhookCalls).toBe(0)
    expect(captured).toHaveLength(1)
    expect(captured[0].method).toBe('POST')
    expect(captured[0].authorization).toMatch(/^Bearer .+/)
    expect(captured[0].body).toEqual({ attemptKey: ATTEMPT_KEY, callbackType: 'SUCCESS' })
  })

  test('② 콜백 422 → 오류 안내·페이지 유지·재시도 가능', async ({ page }) => {
    let calls = 0
    await page.route((url) => url.pathname.endsWith('/api/v1/payments/mock-callback'), (route) => {
      calls += 1
      return route.fulfill({ status: 422, json: { code: 'INVALID_CALLBACK', detail: '거부' } })
    })

    await loginAs(page, 'BUYER')
    await gotoClientSide(page, MOCK_PAGE)

    await page.getByTestId('payment-mock-cancel').click()
    await expect(page.getByTestId('payment-mock-error')).toContainText('결제 처리 중 문제가 발생했습니다')
    expect(new URL(page.url()).pathname).toBe('/payment/mock')

    await page.getByTestId('payment-mock-cancel').click()
    await expect.poll(() => calls).toBe(2)
  })
})
