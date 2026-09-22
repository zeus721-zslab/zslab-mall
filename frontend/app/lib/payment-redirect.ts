import { MOCK_PG_ORIGIN } from '~/lib/constants/payment'

/**
 * 결제 시작 응답 redirectUrl의 이동 방식 판정(Track 97 D-209·순수 함수·vitest 대상).
 * - origin이 Mock PG 주소면 쿼리(attemptKey·amount·method)와 Location 헤더의 orderPublicId를 내부 /payment/mock 경로로 옮긴다(외부 PG 미방문).
 * - 그 외 origin은 실 PG 결제창이므로 URL 그대로 외부 이동한다.
 * redirectUrl이 URL로 파싱되지 않으면 `new URL()`이 던지는 TypeError를 그대로 전파한다(기존 goToMockPayment와 동일·호출자 catch가 일반 오류 안내).
 */
export type PaymentRedirect =
  | { kind: 'mock'; path: string }
  | { kind: 'external'; url: string }

export function resolvePaymentRedirect(redirectUrl: string, location: string | null): PaymentRedirect {
  const url = new URL(redirectUrl)
  if (url.origin !== MOCK_PG_ORIGIN) {
    return { kind: 'external', url: redirectUrl }
  }
  const attemptKey = url.searchParams.get('attemptKey') ?? ''
  const amount = url.searchParams.get('amount') ?? ''
  const paymentMethod = url.searchParams.get('method') ?? ''
  const orderPublicId = location ? location.split('/').pop() ?? '' : ''
  return {
    kind: 'mock',
    path:
      `/payment/mock?attemptKey=${encodeURIComponent(attemptKey)}`
      + `&amount=${encodeURIComponent(amount)}&method=${encodeURIComponent(paymentMethod)}`
      + `&orderPublicId=${encodeURIComponent(orderPublicId)}`,
  }
}
