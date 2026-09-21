/**
 * 셀러 상품 통계 API 타입(Track 90-E-3·D-200 `GET /api/v1/seller/stats/products?from&to` 응답 1:1·backend stats/controller/response/SellerProductStats* 실측).
 * 관리자 대응 API 없음. 기간(양끝 포함·365일)만 받고 비교·버킷 없음. BE는 전역 NON_NULL 직렬화라 productKey(soft-delete)·depletionDays(판매 0)는 키 생략 → optional.
 */

/** 판매 상위·하위 1행(order_item.product_name 스냅샷·매출 분해 PRODUCT 축과 같은 정의). */
export interface SellerProductRank {
  productKey?: string | null
  productName: string
  revenue: number
  orderCount: number
  quantity: number
}

/** 미판매(SALE·기간 내 결제 품목 0). 주문이 없어 이름은 현행 product.name·productKey 항상 있음. */
export interface SellerUnsoldProduct {
  productKey: string
  productName: string
  basePrice: number
}

/** 재고 회전(SALE 상품·상품 단위 합산). depletionDays = ceil(가용 × 기간일수 ÷ 판매)·판매 0이면 생략·가용 0이면 0. */
export interface SellerStockTurnover {
  productKey: string
  productName: string
  inboundQuantity: number
  soldQuantity: number
  availableQuantity: number
  depletionDays?: number | null
}

export interface SellerProductStatsResponse {
  periodDays: number
  topProducts: SellerProductRank[]
  bottomProducts: SellerProductRank[]
  unsoldProducts: SellerUnsoldProduct[]
  stockTurnover: SellerStockTurnover[]
  /** 현재 시점(기간 무관): 판매 중 옵션 중 가용 재고 0. */
  soldOutOptionCount: number
  saleOptionCount: number
}
