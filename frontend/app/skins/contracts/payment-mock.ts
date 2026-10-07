export type MockPaymentCallbackType = 'SUCCESS' | 'FAILURE' | 'CANCEL'

/** pages/payment/mock.vue → PaymentMockView. resultMessage가 있으면 실패·취소로 종결된 상태. */
export interface PaymentMockPageVm {
  hasAttemptKey: boolean
  resultMessage: string
  /** 바로구매 주문이면 실패·취소 후 돌아갈 상품 상세 경로(PF-20), 장바구니 결제면 null. */
  buyNowProductPath: string | null
  methodLabel: string
  amountLabel: string
  errorMessage: string
  submitting: boolean
  pay: (callbackType: MockPaymentCallbackType) => Promise<void>
}
