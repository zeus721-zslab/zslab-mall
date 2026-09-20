import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'
import type { ClaimStatus, OrderItemStatusCode } from '~/lib/constants/claim'

/**
 * 셀러 주문(품목) 상수 단일 소스(Track 90-B-3·관리자 constants/admin-order 복제·CLAUDE.md 4층위 enum 잠금 (4)프론트). 품목·클레임 라벨은
 * 사용자 FE 상수(~/lib/constants/order·claim)를 그대로 쓰고, chip 의미 색상·배송 상태/택배사 라벨·필터/폼 한도만 여기서 정의한다.
 */

/** 품목 상태 chip 의미 색상(라벨은 사용자 ORDER_ITEM_STATUS_LABELS). *_REQUESTED=warning·취소/반품 완료=danger. */
export const SELLER_ORDER_ITEM_STATUS_SEMANTIC: Record<OrderItemStatusCode, SellerSemantic> = {
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

/** 품목 상태 필터 옵션. ORDERED(미결제)는 셀러 목록에서 BE가 제외하므로 옵션에서도 뺀다(D-191). */
export const SELLER_ORDER_ITEM_STATUS_CODES: OrderItemStatusCode[] = [
  'PAID', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED', 'CANCEL_REQUESTED', 'CANCELLED', 'RETURN_REQUESTED', 'RETURNED',
  'EXCHANGE_REQUESTED', 'EXCHANGED',
]

/** 클레임 상태 chip 의미 색상(라벨은 사용자 CLAIM_STATUS_LABELS). */
export const SELLER_CLAIM_STATUS_SEMANTIC: Record<ClaimStatus, SellerSemantic> = {
  REQUESTED: 'warning',
  APPROVED: 'info',
  REJECTED: 'danger',
  COMPLETED: 'success',
}

/** BE DeliveryStatus 3값(READY → SHIPPING → DELIVERED 직진). */
export type SellerDeliveryStatus = 'READY' | 'SHIPPING' | 'DELIVERED'

export const SELLER_DELIVERY_STATUS_LABEL: Record<SellerDeliveryStatus, string> = {
  READY: '배송준비',
  SHIPPING: '배송중',
  DELIVERED: '배송완료',
}

export const SELLER_DELIVERY_STATUS_SEMANTIC: Record<SellerDeliveryStatus, SellerSemantic> = {
  READY: 'info',
  SHIPPING: 'info',
  DELIVERED: 'success',
}

export const SELLER_DELIVERY_STATUS_OPTIONS: { value: SellerDeliveryStatus; title: string }[] = (
  ['READY', 'SHIPPING', 'DELIVERED'] as SellerDeliveryStatus[]
).map((value) => ({ value, title: SELLER_DELIVERY_STATUS_LABEL[value] }))

/** BE DeliveryCarrier 4값(delivery.carrier ENUM). 출고·송장 정정 select의 유일한 출처. */
export type SellerDeliveryCarrier = 'CJ' | 'HANJIN' | 'POST' | 'LOGEN'

export const SELLER_DELIVERY_CARRIER_LABEL: Record<SellerDeliveryCarrier, string> = {
  CJ: 'CJ대한통운',
  HANJIN: '한진택배',
  POST: '우체국택배',
  LOGEN: '로젠택배',
}

export const SELLER_DELIVERY_CARRIER_OPTIONS: { value: SellerDeliveryCarrier; title: string }[] = (
  ['CJ', 'HANJIN', 'POST', 'LOGEN'] as SellerDeliveryCarrier[]
).map((value) => ({ value, title: SELLER_DELIVERY_CARRIER_LABEL[value] }))

/** 출고(prepare-shipment)가 허용되는 품목 상태(BE OrderShippingService·PAID만). */
export const SELLER_SHIPPABLE_ITEM_STATUSES: readonly OrderItemStatusCode[] = ['PAID']

/** 페이지 크기 옵션(BE size 1~100 클램프·관리자와 동일 3단). */
export const SELLER_ORDER_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_SELLER_ORDER_PAGE_SIZE = 20

/** 검색어 최대 길이(BE SellerOrderItemQueryService MAX_KEYWORD_LENGTH). */
export const SELLER_ORDER_KEYWORD_MAX = 50
/** 송장번호 최대 길이(BE PrepareShipmentRequest.trackingNo @Size(max=100)). */
export const SELLER_TRACKING_NO_MAX = 100
