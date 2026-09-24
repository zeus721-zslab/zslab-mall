export type MockPaymentCallbackType = 'SUCCESS' | 'FAILURE' | 'CANCEL'

/** pages/payment/mock.vue → PaymentMockView. resultMessage가 있으면 실패·취소로 종결된 상태. */
export interface PaymentMockPageVm {
  hasAttemptKey: boolean
  resultMessage: string
  methodLabel: string
  amountLabel: string
  errorMessage: string
  submitting: boolean
  pay: (callbackType: MockPaymentCallbackType) => Promise<void>
}
