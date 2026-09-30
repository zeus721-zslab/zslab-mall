import type { CheckoutRequest, CheckoutResponse, OrderCreateRequest, PaymentMethod } from '~/types/checkout'

/** 체크아웃 호출 결과. status(201 신규·200 멱등 캐시)·Location 헤더(신규만 존재)를 응답 본문과 함께 노출한다. */
export interface CheckoutResult {
  data: CheckoutResponse
  status: number
  location: string | null
}

/**
 * 장바구니 체크아웃(POST /api/v1/cart/checkout·조작·recon §6). $fetch.raw로 status·Location 헤더까지 캡처한다
 * (201 + Location: /api/v1/orders/{orderPublicId} · 200 멱등 캐시는 Location 없음). 실패(401/403/422/400)는 throw해
 * 호출부(try/catch)가 처리하고, INITIATE_FAILED(payment.publicId=null)는 2xx라 반환값에 담아 호출부가 판단한다.
 *
 * Idempotency-Key는 요청마다 crypto.randomUUID()로 생성한다(형식 ^[0-9A-Za-z-]{1,128}$ 충족). 동일 폼을 재제출하면
 * 새 키가 발급되어 신규 주문이 되며, 브라우저 재시도(동일 호출 반복)만 멱등 캐시(200)로 수렴한다.
 */
export function useCheckout() {
  const api = useBuyerApi()

  async function submit(request: CheckoutRequest): Promise<CheckoutResult> {
    return postOrder('/v1/cart/checkout', request)
  }

  /**
   * 바로구매 직접 주문(POST /api/v1/orders · FE-100). 장바구니를 거치지 않고 품목을 직접 보낸다. 응답·Idempotency-Key·오류 처리는
   * 장바구니 결제와 같다(같은 CheckoutService · 201 + Location · 422 ORDER_NOT_PAYABLE 등).
   */
  async function submitOrder(request: OrderCreateRequest): Promise<CheckoutResult> {
    return postOrder('/v1/orders', request)
  }

  async function postOrder(path: string, body: CheckoutRequest | OrderCreateRequest): Promise<CheckoutResult> {
    const response = await api.raw<CheckoutResponse>(path, {
      method: 'POST',
      headers: {
        'Idempotency-Key': crypto.randomUUID(),
      },
      body,
    })

    return {
      data: response._data as CheckoutResponse,
      status: response.status,
      location: response.headers.get('Location'),
    }
  }

  /**
   * 모의 결제 콜백 전송(POST /api/v1/payments/mock-callback·BUYER 인증·Track 93 D-198). 무인증 webhook(/api/webhooks/payments)은
   * gateway가 외부 차단하므로 브라우저는 인가된 mock endpoint로 SUCCESS/FAILURE/CANCEL을 통지한다. attemptKey·callbackType만 보내며
   * provider·pgTid·occurredAt은 서버가 생성한다. 실패(4xx/5xx)는 throw해 호출부(try/catch)가 처리하고, 200은 void 반환한다.
   */
  async function sendPaymentCallback(params: {
    attemptKey: string
    callbackType: 'SUCCESS' | 'FAILURE' | 'CANCEL'
  }): Promise<void> {
    const { attemptKey, callbackType } = params
    await api('/v1/payments/mock-callback', {
      method: 'POST',
      body: { attemptKey, callbackType },
    })
  }

  /**
   * 재결제(POST /api/v1/orders/{orderPublicId}/payments·BE BuyerOrderController §6·D-60). 결제대기 주문에 새 Payment를 만들고
   * 체크아웃과 같은 응답(payment.redirectUrl + Location)을 돌려주므로 호출부는 goToPayment(resolvePaymentRedirect)를 그대로 쓴다.
   * 실패(401/404/422 — 이미 결제됨·취소됨 등)는 throw해 호출부가 안내한다.
   */
  async function retryPayment(orderPublicId: string, method: PaymentMethod): Promise<CheckoutResult> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useSellerApi 호출부 선례).
    const path: string = `/v1/orders/${orderPublicId}/payments`
    const response = await api.raw<CheckoutResponse>(path, {
      method: 'POST',
      body: { method },
    })

    return {
      data: response._data as CheckoutResponse,
      status: response.status,
      location: response.headers.get('Location'),
    }
  }

  return { submit, submitOrder, sendPaymentCallback, retryPayment }
}
