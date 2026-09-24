import type { OrderDetail, OrderItem } from '~/types/order'
import type { PaymentMethod } from '~/types/checkout'
import type { ClaimType } from '~/lib/constants/claim'
import type { AUTO_CONFIRM_GUIDE, ITEM_CONFIRM_WARNING, PAYMENT_EXPIRE_GUIDE, orderStatusLabel } from '~/lib/constants/order'
import type { claimableTypes, claimTypeLabel, orderItemStatusLabel } from '~/lib/constants/claim'
import type { PAYMENT_METHODS } from '~/lib/constants/payment'
import type { canResumePayment, isPaymentExpired, PAYMENT_EXPIRED_NOTICE } from '~/lib/utils/payment-resume'

export interface ItemConfirmNotice {
  orderItemId: string
  tone: 'success' | 'error'
  text: string
}

/**
 * pages/orders/[orderPublicId].vue → OrderDetailView. confirmTargetId는 품목별 구매확정 확인 패널을 연 품목 id(뷰가 취소 시 null로 닫는다).
 * payMethod는 결제 재개 결제수단(v-model).
 */
export interface OrderDetailPageVm {
  pending: boolean
  error: Error | undefined
  data: OrderDetail | undefined
  refresh: () => Promise<void>
  errorMessage: string
  formatPrice: (value: number) => string
  goClaim: (item: OrderItem, type: ClaimType) => void
  payMethod: PaymentMethod
  paying: boolean
  payError: string
  submitResumePayment: () => Promise<void>
  confirmTargetId: string | null
  confirming: boolean
  confirmNotice: ItemConfirmNotice | null
  openConfirm: (item: OrderItem) => void
  submitConfirm: (item: OrderItem) => Promise<void>
  AUTO_CONFIRM_GUIDE: typeof AUTO_CONFIRM_GUIDE
  ITEM_CONFIRM_WARNING: typeof ITEM_CONFIRM_WARNING
  PAYMENT_EXPIRE_GUIDE: typeof PAYMENT_EXPIRE_GUIDE
  PAYMENT_EXPIRED_NOTICE: typeof PAYMENT_EXPIRED_NOTICE
  PAYMENT_METHODS: typeof PAYMENT_METHODS
  orderStatusLabel: typeof orderStatusLabel
  orderItemStatusLabel: typeof orderItemStatusLabel
  claimableTypes: typeof claimableTypes
  claimTypeLabel: typeof claimTypeLabel
  canResumePayment: typeof canResumePayment
  isPaymentExpired: typeof isPaymentExpired
}
