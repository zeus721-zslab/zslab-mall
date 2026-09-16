/**
 * 목록 복귀 경로(FE-26·FE-27 base 파라미터화): ?back= 값은 지정한 목록 경로(쿼리 포함)만 허용·그 외는 그 목록 기본 경로.
 * 오픈 리다이렉트·타 화면 이동 방지. 기본 base는 상품 목록(FE-26 호출부 무변경).
 */
export const ADMIN_PRODUCTS_PATH = '/admin/products'
export const ADMIN_ORDERS_PATH = '/admin/orders'

export function resolveBackPath(back: unknown, base: string = ADMIN_PRODUCTS_PATH): string {
  const value = Array.isArray(back) ? back[0] : back
  if (typeof value !== 'string') return base
  if (value === base || value.startsWith(`${base}?`)) return value
  return base
}
