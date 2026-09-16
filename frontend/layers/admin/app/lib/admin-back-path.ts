/**
 * 목록 복귀 경로(FE-26·FE-27 base 파라미터화): ?back= 값은 지정한 목록 경로(쿼리 포함)만 허용·그 외는 그 목록 기본 경로.
 * 오픈 리다이렉트·타 화면 이동 방지. 기본 base는 상품 목록(FE-26 호출부 무변경).
 */
export const ADMIN_PRODUCTS_PATH = '/admin/products'
export const ADMIN_ORDERS_PATH = '/admin/orders'
export const ADMIN_CLAIMS_PATH = '/admin/orders/claims'

/**
 * 허용 base 외 추가 진입 목록(FE-28): 주문 상세는 주문 목록·클레임 목록 양쪽에서 진입하므로 back이 클레임 목록(쿼리 포함)이면 그대로
 * 복귀한다. 그 외 화면(상품 등)은 기존처럼 base만 허용한다.
 */
const EXTRA_BACK_BASES: Record<string, string[]> = {
  [ADMIN_ORDERS_PATH]: [ADMIN_CLAIMS_PATH],
}

function matchesBase(value: string, base: string): boolean {
  return value === base || value.startsWith(`${base}?`)
}

export function resolveBackPath(back: unknown, base: string = ADMIN_PRODUCTS_PATH): string {
  const value = Array.isArray(back) ? back[0] : back
  if (typeof value !== 'string') return base
  if (matchesBase(value, base)) return value
  if ((EXTRA_BACK_BASES[base] ?? []).some((extra) => matchesBase(value, extra))) return value
  return base
}
