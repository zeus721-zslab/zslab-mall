/** 목록 복귀 경로(FE-26): ?back= 값은 /admin/products(쿼리 포함)만 허용·그 외는 기본 목록. 오픈 리다이렉트·타 화면 이동 방지. */
export const ADMIN_PRODUCTS_PATH = '/admin/products'

export function resolveBackPath(back: unknown): string {
  const value = Array.isArray(back) ? back[0] : back
  if (typeof value !== 'string') return ADMIN_PRODUCTS_PATH
  if (value === ADMIN_PRODUCTS_PATH || value.startsWith(`${ADMIN_PRODUCTS_PATH}?`)) return value
  return ADMIN_PRODUCTS_PATH
}
