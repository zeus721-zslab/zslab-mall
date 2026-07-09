import type { PaymentMethod } from '~/types/checkout'

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
