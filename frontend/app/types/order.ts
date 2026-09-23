/**
 * 주문 조회 응답 타입(FE-12a·BE OrderSummaryResponse·OrderResponse·PagedResponse 대응). 목록(OrderSummary)과
 * 단건(OrderDetail)을 미러한다. 식별자는 전부 public_id 문자열. 상태 라벨은 BE가 label=code로 내려주므로
 * (StatusView fallback) 표시용 한글 라벨은 FE 단일 소스(lib/constants/order.ts)에서 파생한다.
 */

import type { ShippingAddress } from '~/types/checkout'
import type { ClaimType } from '~/lib/constants/claim'
import type { DeliveryCarrier, DeliveryStatus } from '~/lib/constants/delivery'

/** UI 노출 상태(BE StatusView 대응). BE는 label=code로 내려줌 → 표시엔 lib/constants/order.ts 라벨 사용. */
export interface StatusView {
  code: string
  label: string
}

/** 페이징 래퍼(BE PagedResponse 대응·필드 5개 한정). */
export interface PagedResponse<T> {
  items: T[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 주문에 걸린 진행 중 클레임의 유형별 건수(BE OrderSummaryResponse.ActiveClaimCount·Track 101-B). */
export interface ActiveClaimCount {
  claimType: ClaimType
  count: number
}

/** 주문 목록 항목(BE OrderSummaryResponse 대응). previewTitle은 서버 생성 문자열, orderedAt은 ISO 문자열. */
export interface OrderSummary {
  orderId: string
  previewTitle: string
  sellerCount: number
  totalPrice: number
  status: StatusView
  orderedAt: string
  /** 진행 중(REQUESTED·APPROVED) 클레임 유형별 건수(FE-63). 없으면 빈 배열. */
  activeClaims: ActiveClaimCount[]
}

/** 품목의 원 발송 배송 정보(BE OrderItemDeliveryResponse·Track 96-2 FE-54). shippedAt·deliveredAt은 ISO(+09:00) 문자열·미도달 시 null. */
export interface OrderItemDelivery {
  carrier: DeliveryCarrier
  trackingNo: string | null
  status: DeliveryStatus
  shippedAt: string | null
  deliveredAt: string | null
}

/**
 * 주문 품목(BE OrderItemResponse 대응). 식별자·productName은 삭제 상품 시 NON_NULL로 생략되므로 optional/null 허용.
 * productName은 표시용 enrich 값(public_id 아님). status는 품목 상태(Track 68 item_status·BE NOT NULL이라 필수).
 */
export interface OrderItem {
  orderItemId: string
  productId?: string | null
  productName?: string | null
  variantId?: string | null
  quantity: number
  unitPrice: number
  totalPrice: number
  /** 주문 시점 옵션 라벨 스냅샷(Track 75·D-164). 단순상품·V20 이전 주문은 NON_NULL로 생략된다. */
  optionLabel?: string | null
  status: StatusView
  /** 완료된 교환이 있는 품목인지(Track 83 D-177 보충·FE-30-4). true면 교환 요청 버튼을 숨긴다(재교환 BE 422). */
  exchangeCompleted?: boolean
  /** 원 발송(OUTBOUND·클레임 미연결) 최신 배송 정보(Track 96-2 FE-54). 송장 미등록이면 NON_NULL로 생략된다. */
  delivery?: OrderItemDelivery | null
}

/** seller 단위 그룹(BE SellerGroupResponse 대응). 단일 판매자 주문도 배열 길이 1. */
export interface SellerGroup {
  sellerId: string
  companyName: string
  items: OrderItem[]
  subtotal: number
}

/** 구매확정 응답(BE ConfirmPurchaseResponse 대응·Track 96-1 FE-53). status는 전이 후 품목 상태 code(CONFIRMED). */
export interface ConfirmPurchaseResponse {
  orderItemId: string
  status: string
}

/**
 * 주문 단건(BE OrderResponse 대응). shippingAddress는 스냅샷 부재 시 null. 배송지는 요청용과 필드가 동일해
 * checkout.ts의 ShippingAddress를 재사용한다.
 */
export interface OrderDetail {
  orderId: string
  status: StatusView
  sellers: SellerGroup[]
  totalPrice: number
  shippingAddress: ShippingAddress | null
}
