import type { OrderStatusCode } from '~/lib/constants/order'
import type { ClaimInspectionResult, ClaimReasonCode, ClaimRejectReasonCode, ClaimStatus, ClaimType, OrderItemStatusCode, RefundStatus } from '~/lib/constants/claim'
import type { PaymentMethod } from '~/types/checkout'
import type {
  AdminDeliveryCarrier,
  AdminDeliveryStatus,
  AdminOrderAction,
  AdminOrderSort,
  AdminPaymentStatus,
} from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 주문 API 타입(FE-27·Track 79 D-168 BE 계약). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 오프셋 없는 LocalDateTime(예: 2026-09-16T10:00:00)이며 formatDateTime(앞 16자 슬라이스)으로만 표시한다.
 */

/** 목록 행(BE AdminOrderSummaryResponse). sellerNames 원소는 셀러 미존재 시 null일 수 있다. */
export interface AdminOrderSummary {
  orderId: string
  orderNo: string
  orderedAt: string
  /** 대표 결제 행(PAID)의 승인 시각. 미결제·실패·만료는 생략(FE-27 보강). */
  paidAt?: string
  status: OrderStatusCode
  buyerName?: string
  buyerEmail?: string
  sellerNames: (string | null)[]
  productSummary: string
  itemCount: number
  paymentAmount: number
  shippingFee: number
  paymentMethod?: PaymentMethod
  paymentStatus?: AdminPaymentStatus
  deliveryStatus?: AdminDeliveryStatus
  claimInProgress: boolean
  actions: AdminOrderAction[]
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface AdminOrderListResponse {
  items: AdminOrderSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 상세(BE AdminOrderDetailResponse). */
export interface AdminOrderDetail {
  orderId: string
  orderNo: string
  orderedAt: string
  paidAt?: string
  status: OrderStatusCode
  buyer?: AdminOrderBuyer
  shippingAddress?: AdminOrderShippingAddress
  totalPrice: number
  discountAmount: number
  shippingFee: number
  paymentAmount: number
  payments: AdminOrderPayment[]
  items: AdminOrderItem[]
  /** 미결제 관리자 취소의 audit 기록(Claim 없는 경로). 결제 후 취소 사유는 items[].claims. */
  cancelReasons: AdminOrderCancelReason[]
  actions: AdminOrderAction[]
}

export interface AdminOrderBuyer {
  userId: string
  name?: string
  email?: string
}

export interface AdminOrderShippingAddress {
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun?: string
  addressDetail?: string
  deliveryMemo?: string
}

export interface AdminOrderPayment {
  paymentId: string
  method: PaymentMethod
  status: AdminPaymentStatus
  amount: number
  pgProvider?: string
  /** PG 거래번호·실패코드(Track 89-A·PG 대사용). 없으면 생략. */
  pgTid?: string
  failureCode?: string
  paidAt?: string
  createdAt: string
}

/** 수동 결제 취소 요청·응답(BE AdminPaymentController mark-cancelled·Track 89-A). NO-OP면 status가 바뀌지 않은 채 200. */
export interface AdminPaymentCancelRequest {
  reason: string
}

export interface AdminPaymentCancelResponse {
  paymentPublicId: string
  status: AdminPaymentStatus
}

export interface AdminOrderItem {
  orderItemId: string
  productName: string
  optionLabel?: string
  quantity: number
  unitPrice: number
  totalPrice: number
  status: OrderItemStatusCode
  sellerName?: string
  delivery?: AdminOrderDelivery
  claims: AdminOrderClaim[]
}

export interface AdminOrderDelivery {
  deliveryId: string
  carrier: AdminDeliveryCarrier
  trackingNo: string
  status: AdminDeliveryStatus
  shippedAt?: string
  deliveredAt?: string
}

export interface AdminOrderClaim {
  claimId: string
  type: ClaimType
  status: ClaimStatus
  reasonCode: string
  reasonDetail?: string
  requestedBy?: number
  requestedAt: string
  processedAt?: string
  /** 사용자 승인형 Claim(REQUESTED)에 대한 승인/거절 버튼 노출 여부. */
  approvable: boolean
  /** 거부 사유 코드·메모(FE-28·Track 80 D-169). 거부 전 생략. */
  rejectReasonCode?: ClaimRejectReasonCode
  rejectMemo?: string
  /** 최신 환불 상태(FE-28). 환불 미생성 시 생략. */
  refundStatus?: RefundStatus
  /** 반품 회수 송장(Track 81-A). 구매자 미등록 시 생략. */
  returnCarrier?: AdminDeliveryCarrier
  returnTrackingNo?: string
  pickedUpAt?: string
  /** 검수 결과(PASS|FAIL)·재입고 여부. 미검수 시 생략. */
  inspectionResult?: ClaimInspectionResult
  restock?: boolean
  /** 교환 전/후 옵션 라벨(EXCHANGE·FE-30·D-177). 비교환·미해소면 생략. */
  originalOptionLabel?: string
  exchangeOptionLabel?: string
  /** 반품 사진 URL(순서 보존·Track 81-B). 없으면 빈 목록(구 픽스처 방어로 optional). */
  attachmentUrls?: string[]
}

export interface AdminOrderCancelReason {
  reasonCode: string
  reasonDetail?: string
  actorUserId?: number
  actorRole?: string
  recordedAt: string
}

/** 취소 요청(BE AdminOrderCancelRequest). 미결제 주문은 orderItemPublicIds를 무시한다. */
export interface AdminOrderCancelRequest {
  reasonCode: ClaimReasonCode
  reasonDetail?: string
  orderItemPublicIds?: string[]
}

/** 취소 응답(BE AdminOrderCancelResponse). 미결제 종료는 orderStatus=PAYMENT_EXPIRED·claims 빈 배열. */
export interface AdminOrderCancelResponse {
  orderId: string
  orderStatus: OrderStatusCode
  claims: { claimId: string; orderItemId: string; status: ClaimStatus }[]
}

/** 송장 등록 요청(BE PrepareShipmentRequest). */
export interface AdminShipmentRequest {
  carrier: AdminDeliveryCarrier
  trackingNo: string
}

/** 송장 등록·배송완료 응답(BE PrepareShipmentResponse·RegisterExchangeShipmentResponse 동일 4필드). */
export interface AdminDeliveryResponse {
  deliveryPublicId: string
  status: AdminDeliveryStatus
  carrier: AdminDeliveryCarrier
  trackingNo: string
}

/** 클레임 승인/거절 응답(BE ClaimResponse). */
export interface AdminClaimResponse {
  publicId: string
  orderItemPublicId: string
  claimType: ClaimType
  status: ClaimStatus
  reasonCode: string
  reasonDetail?: string
  requestedAt: string
  processedAt?: string
  rejectReasonCode?: ClaimRejectReasonCode
  rejectMemo?: string
  refundStatus?: RefundStatus
}

/** 목록 화면 상태 = URL query 단일 소스. from/to는 yyyy-MM-dd(날짜만·API 전송 시 시각 부착). */
export interface AdminOrderListQuery {
  keyword: string
  status: OrderStatusCode | null
  paymentStatus: AdminPaymentStatus | null
  deliveryStatus: AdminDeliveryStatus | null
  from: string | null
  to: string | null
  sort: AdminOrderSort
  page: number
  size: number
}

/** BE GET /admin/orders 쿼리 파라미터(null·빈 값은 제외). */
export type AdminOrderApiParams = Record<string, string | number>

/** BE 400 VALIDATION_FAILED의 fieldErrors 항목. */
export interface AdminFieldError {
  field: string
  message: string
}
