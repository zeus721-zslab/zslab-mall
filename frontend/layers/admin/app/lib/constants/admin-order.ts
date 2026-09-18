import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type { OrderStatusCode } from '~/lib/constants/order'
import type { ClaimStatus, OrderItemStatusCode, RefundStatus } from '~/lib/constants/claim'
import type { PaymentMethod } from '~/types/checkout'
import { PAYMENT_METHODS } from '~/lib/constants/payment'

/**
 * 관리자 주문 관리 상수 단일 소스(FE-27·CLAUDE.md 4층위 enum 잠금 (4)프론트). 주문·품목·클레임·사유 라벨은 사용자 FE 상수
 * (~/lib/constants/order·claim·payment)를 그대로 쓰고, 사용자 화면이 노출하지 않는 결제상태·배송상태·택배사 라벨과 chip 의미
 * 색상만 여기서 정의한다(recon-report-fe-27 C4).
 */

/** BE PaymentStatus 5값. EXPIRED는 미결제 종료(결제창 이탈·30분 만료·시작 실패), FAILED는 재시도 가능한 PG 실패. */
export type AdminPaymentStatus = 'PENDING' | 'PAID' | 'FAILED' | 'CANCELLED' | 'EXPIRED'

export const ADMIN_PAYMENT_STATUS_LABEL: Record<AdminPaymentStatus, string> = {
  PENDING: '결제대기',
  PAID: '결제완료',
  FAILED: '결제실패',
  CANCELLED: '결제취소',
  EXPIRED: '미결제 종료',
}

export const ADMIN_PAYMENT_STATUS_SEMANTIC: Record<AdminPaymentStatus, AdminSemantic> = {
  PENDING: 'warning',
  PAID: 'success',
  FAILED: 'danger',
  CANCELLED: 'danger',
  EXPIRED: 'danger',
}

export const ADMIN_PAYMENT_STATUS_OPTIONS: { value: AdminPaymentStatus; title: string }[] = (
  ['PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED'] as AdminPaymentStatus[]
).map((value) => ({ value, title: ADMIN_PAYMENT_STATUS_LABEL[value] }))

/** BE DeliveryStatus 3값(READY → SHIPPING → DELIVERED 직진). */
export type AdminDeliveryStatus = 'READY' | 'SHIPPING' | 'DELIVERED'

export const ADMIN_DELIVERY_STATUS_LABEL: Record<AdminDeliveryStatus, string> = {
  READY: '배송준비',
  SHIPPING: '배송중',
  DELIVERED: '배송완료',
}

export const ADMIN_DELIVERY_STATUS_SEMANTIC: Record<AdminDeliveryStatus, AdminSemantic> = {
  READY: 'info',
  SHIPPING: 'info',
  DELIVERED: 'success',
}

export const ADMIN_DELIVERY_STATUS_OPTIONS: { value: AdminDeliveryStatus; title: string }[] = (
  ['READY', 'SHIPPING', 'DELIVERED'] as AdminDeliveryStatus[]
).map((value) => ({ value, title: ADMIN_DELIVERY_STATUS_LABEL[value] }))

/** BE DeliveryCarrier 4값(delivery.carrier ENUM). 송장 다이얼로그 select의 유일한 출처. */
export type AdminDeliveryCarrier = 'CJ' | 'HANJIN' | 'POST' | 'LOGEN'

export const ADMIN_DELIVERY_CARRIER_LABEL: Record<AdminDeliveryCarrier, string> = {
  CJ: 'CJ대한통운',
  HANJIN: '한진택배',
  POST: '우체국택배',
  LOGEN: '로젠택배',
}

export const ADMIN_DELIVERY_CARRIER_OPTIONS: { value: AdminDeliveryCarrier; title: string }[] = (
  ['CJ', 'HANJIN', 'POST', 'LOGEN'] as AdminDeliveryCarrier[]
).map((value) => ({ value, title: ADMIN_DELIVERY_CARRIER_LABEL[value] }))

/** 주문 상태 chip 의미 색상(라벨은 사용자 ORDER_STATUS_LABELS). 종료(취소·미결제 종료)=danger·대기/부분취소=warning·완료=success·진행=info. */
export const ADMIN_ORDER_STATUS_SEMANTIC: Record<OrderStatusCode, AdminSemantic> = {
  PENDING_PAYMENT: 'warning',
  PAID: 'info',
  PREPARING: 'info',
  SHIPPING: 'info',
  DELIVERED: 'success',
  CONFIRMED: 'success',
  CANCELLED: 'danger',
  PARTIAL_CANCEL: 'warning',
  PAYMENT_EXPIRED: 'danger',
}

/** 주문 상태 필터 옵션(BE OrderStatus 9값 전부·라벨은 사용자 상수). */
export const ADMIN_ORDER_STATUS_CODES: OrderStatusCode[] = [
  'PENDING_PAYMENT', 'PAID', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED', 'CANCELLED', 'PARTIAL_CANCEL', 'PAYMENT_EXPIRED',
]

/** 품목 상태 chip 의미 색상(라벨은 사용자 ORDER_ITEM_STATUS_LABELS). *_REQUESTED=warning·취소/반품 완료=danger. */
export const ADMIN_ORDER_ITEM_STATUS_SEMANTIC: Record<OrderItemStatusCode, AdminSemantic> = {
  ORDERED: 'info',
  PAID: 'info',
  PREPARING: 'info',
  SHIPPING: 'info',
  DELIVERED: 'success',
  CONFIRMED: 'success',
  CANCEL_REQUESTED: 'warning',
  CANCELLED: 'danger',
  RETURN_REQUESTED: 'warning',
  RETURNED: 'danger',
  EXCHANGE_REQUESTED: 'warning',
  EXCHANGED: 'info',
}

/** 클레임 상태 chip 의미 색상(라벨은 사용자 CLAIM_STATUS_LABELS). */
export const ADMIN_CLAIM_STATUS_SEMANTIC: Record<ClaimStatus, AdminSemantic> = {
  REQUESTED: 'warning',
  APPROVED: 'info',
  REJECTED: 'danger',
  COMPLETED: 'success',
}

/** 환불 상태 chip 의미 색상(FE-28·라벨은 사용자 REFUND_STATUS_LABELS). */
export const ADMIN_REFUND_STATUS_SEMANTIC: Record<RefundStatus, AdminSemantic> = {
  PENDING: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
}

/** 수동 결제 취소 사유 최대 길이(BE AdminPaymentMarkCancelledRequest @Size(max=200)·Track 89-A). */
export const ADMIN_PAYMENT_CANCEL_REASON_MAX = 200

/** 결제수단 code → 사용자 라벨(PAYMENT_METHODS 단일 소스). 매핑에 없는 값은 원본 폴백. */
export function paymentMethodLabel(code: string): string {
  const found = PAYMENT_METHODS.find((option) => option.value === (code as PaymentMethod))
  return found ? found.label : code
}

/** BE AdminOrderSummaryResponse.actions 값. */
export type AdminOrderAction = 'CANCEL' | 'PREPARE_SHIPMENT' | 'MARK_DELIVERED'

/** BE AdminOrderSort. */
export type AdminOrderSort = 'LATEST' | 'OLDEST'

export const ADMIN_ORDER_SORT_OPTIONS: { value: AdminOrderSort; title: string }[] = [
  { value: 'LATEST', title: '최신순' },
  { value: 'OLDEST', title: '오래된순' },
]

export const DEFAULT_ADMIN_ORDER_SORT: AdminOrderSort = 'LATEST'

/** 페이지 크기 옵션(BE size 1~100 클램프·상품 목록과 동일 3단). */
export const ADMIN_ORDER_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_ADMIN_ORDER_PAGE_SIZE = 20

/** 취소 메모 최대 길이(BE AdminOrderCancelRequest.reasonDetail @Size(max=500)·사용자 클레임 폼과 동일). */
export const ADMIN_ORDER_CANCEL_DETAIL_MAX = 500
/** 송장번호 최대 길이(BE PrepareShipmentRequest.trackingNo @Size(max=100)). */
export const ADMIN_ORDER_TRACKING_NO_MAX = 100
/** 검색어 최대 길이(BE AdminOrderQueryService MAX_KEYWORD_LENGTH). */
export const ADMIN_ORDER_KEYWORD_MAX = 50
