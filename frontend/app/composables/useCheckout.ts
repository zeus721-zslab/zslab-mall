import type { CheckoutRequest, CheckoutResponse } from '~/types/checkout'

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
  const config = useRuntimeConfig()
  const auth = useAuthStore()

  async function submit(request: CheckoutRequest): Promise<CheckoutResult> {
    // API base 이원화(recon §2·store와 동일): SSR은 내부 직결, 브라우저는 동일 Origin 상대경로.
    const baseURL = import.meta.server
      ? `${config.apiInternalBase}/api`
      : config.public.apiBase || '/api'

    const response = await $fetch.raw<CheckoutResponse>('/v1/cart/checkout', {
      baseURL,
      method: 'POST',
      headers: {
        Authorization: `Bearer ${auth.token}`,
        'Idempotency-Key': crypto.randomUUID(),
      },
      body: request,
    })

    return {
      data: response._data as CheckoutResponse,
      status: response.status,
      location: response.headers.get('Location'),
    }
  }

  /**
   * 모의 PG webhook 콜백 전송(POST /api/webhooks/payments·permitAll·Bearer 불요). SUCCESS/FAILURE/CANCEL을
   * 서버에 통지한다. occurredAt은 timezone 없는 LocalDateTime이라 Z를 제거한다(slice(0,23) — Z 포함 시 400).
   * pgTid는 SUCCESS에만 생성값, metadata는 FAILURE + failureCode일 때만 { failureCode }. 실패(4xx/5xx)는 throw해
   * 호출부(try/catch)가 처리하고, 200은 void 반환한다.
   */
  async function sendPaymentCallback(params: {
    attemptKey: string
    callbackType: 'SUCCESS' | 'FAILURE' | 'CANCEL'
    failureCode?: string
  }): Promise<void> {
    // API base 이원화(submit과 동일): SSR 내부 직결, 브라우저 동일 Origin 상대경로.
    const baseURL = import.meta.server
      ? `${config.apiInternalBase}/api`
      : config.public.apiBase || '/api'

    const { attemptKey, callbackType, failureCode } = params
    await $fetch(`${baseURL}/webhooks/payments`, {
      method: 'POST',
      body: {
        provider: 'MOCK_PG',
        callbackType,
        paymentAttemptKey: attemptKey,
        pgTid: callbackType === 'SUCCESS' ? `mocktid_${crypto.randomUUID().slice(0, 12)}` : null,
        occurredAt: new Date().toISOString().slice(0, 23), // Z 제거(LocalDateTime 정합)
        metadata: callbackType === 'FAILURE' && failureCode ? { failureCode } : null,
      },
    })
  }

  return { submit, sendPaymentCallback }
}
