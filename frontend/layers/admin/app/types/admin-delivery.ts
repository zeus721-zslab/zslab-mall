import type { ClaimStatus, ClaimType, OrderItemStatusCode } from '~/lib/constants/claim'
import type { AdminDeliveryCarrier, AdminDeliveryStatus } from '#layers/admin/app/lib/constants/admin-order'
import type { AdminDeliveryDirection, AdminDeliveryScope, AdminDeliverySort } from '#layers/admin/app/lib/constants/admin-delivery'
import type { AdminOrderShippingAddress } from '#layers/admin/app/types/admin-order'

/**
 * 관리자 배송 API 타입(FE-37·Track 89-B D-184 BE 계약). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 KST 오프셋 ISO(KstOffsetSerializer)이며 formatDateTime으로만 표시한다.
 */

/** 목록 행(BE AdminDeliverySummaryResponse). 식별자는 전부 public_id. */
export interface AdminDeliverySummary {
  deliveryId: string
  orderId?: string
  orderNo?: string
  productName?: string
  recipientName?: string
  direction: AdminDeliveryDirection
  status: AdminDeliveryStatus
  carrier: AdminDeliveryCarrier
  trackingNo?: string
  shippedAt?: string
  deliveredAt?: string
  /** 클레임 연계 배송(교환품 발송·재발송·회수)만. 원 발송은 생략. */
  claimId?: string
  claimType?: ClaimType
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface AdminDeliveryListResponse {
  items: AdminDeliverySummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 상세(BE AdminDeliveryDetailResponse). 배송지는 관리자 주문 상세와 같은 스냅샷(마스킹 없음). */
export interface AdminDeliveryDetail extends AdminDeliverySummary {
  orderItemId?: string
  optionLabel?: string
  quantity: number
  orderItemStatus?: OrderItemStatusCode
  shippingAddress?: AdminOrderShippingAddress
  claimStatus?: ClaimStatus
}

/** 송장 정정 요청(BE AdminDeliveryTrackingCorrectionRequest). 응답은 AdminDeliveryResponse(4필드) 재사용. */
export interface AdminDeliveryTrackingCorrectionRequest {
  carrier: AdminDeliveryCarrier
  trackingNo: string
  reason: string
}

/** 목록 화면 상태 = URL query 단일 소스. from/to는 yyyy-MM-dd(발송일·API 전송 시 시각 부착). */
export interface AdminDeliveryListQuery {
  keyword: string
  scope: AdminDeliveryScope
  status: AdminDeliveryStatus | null
  carrier: AdminDeliveryCarrier | null
  from: string | null
  to: string | null
  sort: AdminDeliverySort
  page: number
  size: number
}

/** BE GET /admin/deliveries 쿼리 파라미터(null·빈 값은 제외). */
export type AdminDeliveryApiParams = Record<string, string | number>
