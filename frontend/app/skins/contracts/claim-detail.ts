import type { ClaimDetail } from '~/types/claim'
import type { OrderListTab } from '~/lib/constants/order-tabs'
import type { DeliveryCarrier } from '~/lib/constants/delivery'
import type { TimelineStep, TimelineStepState } from '~/lib/utils/claim-timeline'
import type {
  CLAIM_CANCEL_WARNING,
  CLAIM_INSPECTION_RESULT_LABELS,
  CLAIM_REASON_LABELS,
  REFUND_TIMING_NOTICE,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  refundStatusLabel,
} from '~/lib/constants/claim'
import type {
  DELIVERY_CARRIER_CODES,
  DELIVERY_CARRIER_LABELS,
  DELIVERY_TRACKING_NO_MAX,
  deliveryCarrierLabel,
} from '~/lib/constants/delivery'
import type { formatDateTime } from '~/lib/utils/datetime'

/**
 * pages/claims/[claimPublicId].vue → ClaimDetailView. cancelConfirmOpen은 뷰가 확인 패널 열기·닫기로 직접 바꾼다.
 * 회수 송장 입력(shipmentCarrier·shipmentTrackingNo)은 v-model, 제출·취소는 페이지 함수로만 한다.
 */
export interface ClaimDetailPageVm {
  pending: boolean
  error: Error | undefined
  data: ClaimDetail | undefined
  refresh: () => Promise<void>
  errorMessage: string
  listTab: OrderListTab
  timeline: TimelineStep[]
  stageGuide: string
  stepCircleClass: (state: TimelineStepState) => string
  cancellable: boolean
  cancelConfirmOpen: boolean
  cancelSubmitting: boolean
  cancelError: string
  submitCancel: () => Promise<void>
  shipmentCarrier: DeliveryCarrier | ''
  shipmentTrackingNo: string
  shipmentSubmitting: boolean
  shipmentError: string
  submitReturnShipment: () => Promise<void>
  CLAIM_CANCEL_WARNING: typeof CLAIM_CANCEL_WARNING
  CLAIM_INSPECTION_RESULT_LABELS: typeof CLAIM_INSPECTION_RESULT_LABELS
  CLAIM_REASON_LABELS: typeof CLAIM_REASON_LABELS
  REFUND_TIMING_NOTICE: typeof REFUND_TIMING_NOTICE
  DELIVERY_CARRIER_CODES: typeof DELIVERY_CARRIER_CODES
  DELIVERY_CARRIER_LABELS: typeof DELIVERY_CARRIER_LABELS
  DELIVERY_TRACKING_NO_MAX: typeof DELIVERY_TRACKING_NO_MAX
  claimRejectReasonLabel: typeof claimRejectReasonLabel
  claimStatusLabel: typeof claimStatusLabel
  claimTypeLabel: typeof claimTypeLabel
  refundStatusLabel: typeof refundStatusLabel
  deliveryCarrierLabel: typeof deliveryCarrierLabel
  formatDateTime: typeof formatDateTime
}
