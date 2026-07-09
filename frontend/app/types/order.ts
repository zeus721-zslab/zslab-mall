/**
 * 주문 조회 응답 타입(FE-12a·BE OrderSummaryResponse·OrderResponse·PagedResponse 대응). 목록(OrderSummary)과
 * 단건(OrderDetail)을 미러한다. 식별자는 전부 public_id 문자열. 상태 라벨은 BE가 label=code로 내려주므로
 * (StatusView fallback) 표시용 한글 라벨은 FE 단일 소스(lib/constants/order.ts)에서 파생한다.
 */

import type { ShippingAddress } from '~/types/checkout'

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

/** 주문 목록 항목(BE OrderSummaryResponse 대응). previewTitle은 서버 생성 문자열, orderedAt은 ISO 문자열. */
export interface OrderSummary {
  orderId: string
  previewTitle: string
  sellerCount: number
  totalPrice: number
  status: StatusView
  orderedAt: string
}

/**
 * 주문 품목(BE OrderItemResponse 대응). 식별자·productName은 삭제 상품 시 NON_NULL로 생략되므로 optional/null 허용.
 * productName은 표시용 enrich 값(public_id 아님).
 */
export interface OrderItem {
  orderItemId: string
  productId?: string | null
  productName?: string | null
  variantId?: string | null
  quantity: number
  unitPrice: number
  totalPrice: number
}

/** seller 단위 그룹(BE SellerGroupResponse 대응). 단일 판매자 주문도 배열 길이 1. */
export interface SellerGroup {
  sellerId: string
  companyName: string
  items: OrderItem[]
  subtotal: number
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
