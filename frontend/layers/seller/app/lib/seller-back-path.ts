/**
 * 셀러 화면 경로 단일 소스 + 목록 복귀 경로(Track 90-B-3·관리자 admin-back-path 복제). ?back= 값은 지정한 목록 경로(쿼리 포함) 또는
 * 허용 추가 진입 목록만 통과하고 그 외는 그 목록 기본 경로로 — 오픈 리다이렉트·타 화면 이동 방지.
 */
export const SELLER_DASHBOARD_PATH = '/seller'
export const SELLER_ORDERS_PATH = '/seller/orders'
export const SELLER_DELIVERIES_PATH = '/seller/deliveries'
export const SELLER_SETTLEMENTS_PATH = '/seller/settlements'
export const SELLER_PRODUCTS_PATH = '/seller/products'
export const SELLER_INVENTORY_PATH = '/seller/products/inventory'
export const SELLER_CLAIMS_PATH = '/seller/claims'
export const SELLER_STATS_SALES_PATH = '/seller/stats/sales'
export const SELLER_STATS_ORDERS_PATH = '/seller/stats/orders'
export const SELLER_STATS_PRODUCTS_PATH = '/seller/stats/products'

/**
 * 허용 base 외 추가 진입 목록: 품목 상세는 대시보드(최근 주문)·배송 목록에서도, 클레임 상세는 주문 목록(클레임 칩)에서도, 상품 상세는
 * 매출 통계(분해 표 상품 행·Track 90-E-1)·주문클레임 통계(클레임 상품별 표·90-E-2)·상품 통계(90-E-3)에서도 진입한다.
 */
const EXTRA_BACK_BASES: Record<string, string[]> = {
  [SELLER_ORDERS_PATH]: [SELLER_DASHBOARD_PATH, SELLER_DELIVERIES_PATH],
  [SELLER_CLAIMS_PATH]: [SELLER_ORDERS_PATH],
  [SELLER_PRODUCTS_PATH]: [SELLER_STATS_SALES_PATH, SELLER_STATS_ORDERS_PATH, SELLER_STATS_PRODUCTS_PATH],
}

/** 상세 화면에서의 진입(경로 세그먼트 뒤가 열린 형태): 클레임 상세는 품목 상세(/seller/orders/{oit}·클레임 칩)로 복귀할 수 있다(Track 90-D-1). */
const EXTRA_BACK_DETAIL_PREFIXES: Record<string, string[]> = {
  [SELLER_CLAIMS_PATH]: [`${SELLER_ORDERS_PATH}/`],
}

function matchesBase(value: string, base: string): boolean {
  return value === base || value.startsWith(`${base}?`)
}

export function resolveBackPath(back: unknown, base: string): string {
  const value = Array.isArray(back) ? back[0] : back
  if (typeof value !== 'string') return base
  if (matchesBase(value, base)) return value
  if ((EXTRA_BACK_BASES[base] ?? []).some((extra) => matchesBase(value, extra))) return value
  if ((EXTRA_BACK_DETAIL_PREFIXES[base] ?? []).some((prefix) => value.startsWith(prefix))) return value
  return base
}
