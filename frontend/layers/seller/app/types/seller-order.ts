import type { ClaimStatus, ClaimType, OrderItemStatusCode } from '~/lib/constants/claim'
import type { SellerDeliveryCarrier, SellerDeliveryStatus } from '#layers/seller/app/lib/constants/seller-order'

/**
 * 셀러 주문(품목) API 타입(Track 90-B-3·D-191 BE 계약 1:1·backend order/controller/response/SellerOrderItem* 실측). 셀러의 주문 단위 = 자기 품목 행.
 * nullable(optionLabel·delivery·recipientName)은 BE NON_NULL 직렬화로 생략될 수 있어 optional. 시각 문자열은 KST 오프셋 ISO(KstOffsetSerializer)라
 * formatDateTime으로만 표시한다 — 관리자 주문 응답(오프셋 없는 LocalDateTime)과 형식이 다르므로 관리자 파서를 복제하지 않는다(D-191 §2).
 */

/** 품목의 원 발송(OUTBOUND) 최신 배송(BE SellerOrderItemDeliveryResponse). 송장 미등록 품목은 생략. */
export interface SellerOrderItemDelivery {
  deliveryId: string
  carrier: SellerDeliveryCarrier
  trackingNo: string
  status: SellerDeliveryStatus
  shippedAt?: string
  deliveredAt?: string
}

/** 품목의 요청일 최신 클레임 요약(BE SellerOrderItemClaimResponse·Track 90-D-1). 클레임 없는 품목은 생략. */
export interface SellerOrderItemClaim {
  claimId: string
  type: ClaimType
  status: ClaimStatus
  requestedAt: string
}

/** 목록 행(BE SellerOrderItemSummaryResponse·14필드). 주문 축은 번호·시각뿐(구매자·총액 없음). claim:order_item = 1:N이라 최신 1건 + 건수. */
export interface SellerOrderItemSummary {
  orderItemId: string
  orderNo: string
  orderedAt: string
  paidAt?: string
  productName: string
  optionLabel?: string
  quantity: number
  unitPrice: number
  totalPrice: number
  itemStatus: OrderItemStatusCode
  /** 배송지 수령인명(주문자 아님). */
  recipientName?: string
  delivery?: SellerOrderItemDelivery
  claim?: SellerOrderItemClaim
  /** 품목의 클레임 총 건수(거부·종결 이력 포함·0이면 클레임 없음). */
  claimCount: number
}

/** 배송지 스냅샷(BE ShippingAddressResponse·마스킹 없음·출고 라벨용). */
export interface SellerOrderShippingAddress {
  recipientName: string
  recipientPhone: string
  zonecode: string
  addressRoad: string
  addressJibun?: string
  addressDetail?: string
  deliveryMemo?: string
}

/** 상세(BE SellerOrderItemDetailResponse = 목록 행 − recipientName + shippingAddress). */
export interface SellerOrderItemDetail extends Omit<SellerOrderItemSummary, 'recipientName'> {
  shippingAddress?: SellerOrderShippingAddress
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface SellerOrderItemListResponse {
  items: SellerOrderItemSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 출고 요청(BE PrepareShipmentRequest). */
export interface SellerShipmentRequest {
  carrier: SellerDeliveryCarrier
  trackingNo: string
}

/** 출고·배송완료·송장 정정 응답(BE PrepareShipmentResponse·RegisterExchangeShipmentResponse·SellerDeliveryTrackingCorrectionResponse 공통 4필드). */
export interface SellerDeliveryResponse {
  deliveryPublicId: string
  status: SellerDeliveryStatus
  carrier: SellerDeliveryCarrier
  trackingNo: string
}

/** 목록 화면 상태 = URL query 단일 소스. from/to는 yyyy-MM-dd(결제일·API 전송 시 시각 부착). 정렬은 결제일 최신순 고정(BE·sort 파라미터 없음). */
export interface SellerOrderListQuery {
  keyword: string
  status: OrderItemStatusCode | null
  from: string | null
  to: string | null
  page: number
  size: number
}

/** BE GET /seller/order-items 쿼리 파라미터(null·빈 값은 제외). */
export type SellerOrderApiParams = Record<string, string | number>
