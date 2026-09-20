import type { ClaimType } from '~/lib/constants/claim'
import type { SellerDeliveryCarrier, SellerDeliveryStatus } from '#layers/seller/app/lib/constants/seller-order'
import type { SellerDeliveryDirection, SellerDeliveryScope, SellerDeliverySort } from '#layers/seller/app/lib/constants/seller-delivery'

/**
 * 셀러 배송 API 타입(Track 90-B-3·D-191 BE 계약 1:1·backend delivery/controller/response/SellerDelivery* 실측). nullable 필드는 BE NON_NULL 직렬화로
 * 생략될 수 있어 optional. 시각 문자열은 KST 오프셋 ISO(KstOffsetSerializer)이며 formatDateTime으로만 표시한다. 상세 API는 없고(목록 행에 품목·주문번호·
 * 수령인이 있음) 배송완료·송장 정정은 행에서 바로 처리한다.
 */

/** 목록 행(BE SellerDeliverySummaryResponse·15필드). 주문 축은 주문번호만·품목 축은 품목 public_id·옵션·수량. */
export interface SellerDeliverySummary {
  deliveryId: string
  orderItemId?: string
  orderNo?: string
  productName?: string
  optionLabel?: string
  quantity: number
  /** 배송지 수령인명(주문자 아님). */
  recipientName?: string
  direction: SellerDeliveryDirection
  status: SellerDeliveryStatus
  carrier: SellerDeliveryCarrier
  trackingNo?: string
  shippedAt?: string
  deliveredAt?: string
  /** 클레임 연계 배송(교환품 발송·재발송·회수)만. 원 발송은 생략. */
  claimId?: string
  claimType?: ClaimType
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface SellerDeliveryListResponse {
  items: SellerDeliverySummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 송장 정정 요청(BE SellerDeliveryTrackingCorrectionRequest). 응답은 SellerDeliveryResponse(4필드). */
export interface SellerDeliveryTrackingCorrectionRequest {
  carrier: SellerDeliveryCarrier
  trackingNo: string
  reason: string
}

/** 목록 화면 상태 = URL query 단일 소스. from/to는 yyyy-MM-dd(발송일·API 전송 시 시각 부착). */
export interface SellerDeliveryListQuery {
  keyword: string
  scope: SellerDeliveryScope
  status: SellerDeliveryStatus | null
  carrier: SellerDeliveryCarrier | null
  from: string | null
  to: string | null
  sort: SellerDeliverySort
  page: number
  size: number
}

/** BE GET /seller/deliveries 쿼리 파라미터(null·빈 값은 제외). */
export type SellerDeliveryApiParams = Record<string, string | number>
