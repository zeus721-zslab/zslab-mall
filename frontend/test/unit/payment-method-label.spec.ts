import { describe, it, expect } from 'vitest'
import { PAYMENT_METHODS, paymentMethodLabel } from '~/lib/constants/payment'

// Track 105-4g-3: 주문 상세 결제 수단 라벨은 체크아웃 선택지와 같은 단일 소스(PAYMENT_METHODS)에서 파생한다. 모의 결제도 같은 수단 코드다.
describe('paymentMethodLabel', () => {
  it('BE PaymentMethod 4종 → 한글 라벨', () => {
    expect(paymentMethodLabel('CARD')).toBe('카드')
    expect(paymentMethodLabel('BANK')).toBe('계좌이체')
    expect(paymentMethodLabel('VBANK')).toBe('가상계좌')
    expect(paymentMethodLabel('KAKAO')).toBe('카카오페이')
  })

  it('체크아웃 선택지 라벨과 항상 같다', () => {
    for (const option of PAYMENT_METHODS) {
      expect(paymentMethodLabel(option.value)).toBe(option.label)
    }
  })
})
