import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { gotoClientSide } from './helpers/navigation'
import { MOCK_PG_ORIGIN } from '../app/lib/constants/payment'

/**
 * 구매자 결제 재개(Track 102 FE-64·보완) E2E. 주문 상세와 재결제(POST /api/v1/orders/{id}/payments·D-60)는 page.route로 mock해
 * 로컬 DB를 바꾸지 않고 (a) 결제대기 주문에서 결제 화면까지 이어지는지 (b) 만료 주문에는 버튼이 없고 사유가 뜨는지만 본다.
 * mock PG origin 응답은 기존 결제 흐름과 같은 규약이라 /payment/mock으로 내부 이동한다(lib/payment-redirect).
 */
const PENDING_ORDER = 'ord_E2E00000000000000000010201'
const EXPIRED_ORDER = 'ord_E2E00000000000000000010202'
const ATTEMPT_KEY = 'pat_E2E00000000000000000010201'

function orderDetail(orderId: string, statusCode: string, statusLabel: string): Record<string, unknown> {
  return {
    orderId,
    status: { code: statusCode, label: statusLabel },
    orderedAt: '2026-09-23T10:00:00',
    totalPrice: 19900,
    sellers: [{
      sellerId: 'slr_E2E0000000000000000000001',
      companyName: 'E2E셀러A',
      subtotal: 19900,
      items: [{
        orderItemId: 'oit_E2E0000000000000000000001',
        productName: 'E2E 결제대기 상품',
        quantity: 1,
        unitPrice: 19900,
        totalPrice: 19900,
        status: { code: statusCode === 'PAYMENT_EXPIRED' ? 'ORDERED' : 'ORDERED', label: '주문접수' },
      }],
    }],
    shippingAddress: null,
  }
}

/** 주문 상세 mock. 재결제 POST는 captured에 담고 mock PG redirectUrl + Location을 돌려준다. */
async function mockOrderApi(page: Page, orderId: string, statusCode: string, statusLabel: string): Promise<{ posts: string[] }> {
  const captured = { posts: [] as string[] }
  await page.route((url) => url.pathname.endsWith(`/api/v1/orders/${orderId}/payments`), (route) => {
    captured.posts.push(route.request().postData() ?? '')
    return route.fulfill({
      status: 201,
      headers: { Location: `/api/v1/payments/pay_E2E0000000000000000000001` },
      json: {
        payment: {
          publicId: 'pay_E2E0000000000000000000001',
          status: 'PENDING',
          amount: 19900,
          redirectUrl: `${MOCK_PG_ORIGIN}/checkout?attemptKey=${ATTEMPT_KEY}&amount=19900&method=CARD`,
        },
        next: null,
      },
    })
  })
  await page.route((url) => url.pathname.endsWith(`/api/v1/orders/${orderId}`), (route) =>
    route.fulfill({ json: orderDetail(orderId, statusCode, statusLabel) }))
  return captured
}

test.describe('구매자 결제 재개(Track 102)', () => {
  test('① 결제대기 주문 → 결제하기 → POST 재결제(method) → mock 결제 화면 진입', async ({ page }) => {
    const captured = await mockOrderApi(page, PENDING_ORDER, 'PENDING_PAYMENT', '결제대기')
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/orders/${PENDING_ORDER}`)

    await expect(page.getByTestId('order-resume-payment')).toBeVisible()
    await expect(page.getByTestId('order-payment-expire-guide')).toContainText('30분')

    await page.getByTestId('order-resume-payment-submit').click()

    // mock PG origin 응답은 외부 이동 없이 내부 /payment/mock으로 들어간다(attemptKey·주문번호 승계).
    await page.waitForURL((url) => url.pathname === '/payment/mock')
    const query = new URL(page.url()).searchParams
    expect(query.get('attemptKey')).toBe(ATTEMPT_KEY)
    expect(query.get('orderPublicId')).toBe(PENDING_ORDER)
    await expect(page.getByRole('heading', { name: '모의 결제' })).toBeVisible()

    expect(captured.posts).toHaveLength(1)
    expect(JSON.parse(captured.posts[0]!)).toEqual({ method: 'CARD' })
  })

  test('② 미결제 종료 주문 → 결제하기 없음 · 왜 결제할 수 없는지 안내', async ({ page }) => {
    await mockOrderApi(page, EXPIRED_ORDER, 'PAYMENT_EXPIRED', '미결제 종료')
    await loginAs(page, 'BUYER')
    await gotoClientSide(page, `/orders/${EXPIRED_ORDER}`)

    await expect(page.getByTestId('order-payment-expired-notice')).toContainText('자동 취소된 주문')
    await expect(page.getByTestId('order-resume-payment')).toHaveCount(0)
    await expect(page.getByTestId('order-resume-payment-submit')).toHaveCount(0)
    await expect(page.getByTestId('order-payment-expire-guide')).toHaveCount(0)
  })
})
