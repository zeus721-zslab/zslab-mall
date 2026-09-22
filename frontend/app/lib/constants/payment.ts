import type { PaymentMethod } from '~/types/checkout'

/**
 * Mock PG 결제창 origin(BE MockPaymentGateway.MOCK_CHECKOUT_BASE와 동일·Track 97 D-209). 체크아웃 응답 redirectUrl의 origin이 이 값이면
 * 내부 /payment/mock으로 이동하고, 아니면 실 PG 결제창으로 외부 이동한다(lib/payment-redirect).
 */
export const MOCK_PG_ORIGIN = 'https://mock-pg.zslab.local'

/**
 * 결제수단 단일 소스(FE-11·BE PaymentMethod enum 대응). value는 {@link PaymentMethod} 유니온으로 타입 고정(매직 문자열 방지),
 * label은 UI 표기. 체크아웃 폼의 결제수단 선택지가 이 배열에서 파생된다.
 */
export const PAYMENT_METHODS: { value: PaymentMethod; label: string }[] = [
  { value: 'CARD', label: '카드' },
  { value: 'BANK', label: '계좌이체' },
  { value: 'VBANK', label: '가상계좌' },
  { value: 'KAKAO', label: '카카오페이' },
]
