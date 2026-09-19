/**
 * 목록 복귀 경로(FE-26·FE-27 base 파라미터화): ?back= 값은 지정한 목록 경로(쿼리 포함)만 허용·그 외는 그 목록 기본 경로.
 * 오픈 리다이렉트·타 화면 이동 방지. 기본 base는 상품 목록(FE-26 호출부 무변경).
 */
export const ADMIN_DASHBOARD_PATH = '/admin'
export const ADMIN_PRODUCTS_PATH = '/admin/products'
export const ADMIN_ORDERS_PATH = '/admin/orders'
export const ADMIN_CLAIMS_PATH = '/admin/orders/claims'
export const ADMIN_MEMBERS_PATH = '/admin/members'
export const ADMIN_MEMBERS_WITHDRAWN_PATH = '/admin/members/withdrawn'
export const ADMIN_SELLERS_PATH = '/admin/members/sellers'
export const ADMIN_SETTLEMENTS_PATH = '/admin/settlements'
export const ADMIN_SETTLEMENTS_SELLERS_PATH = '/admin/settlements/sellers'

/**
 * 허용 base 외 추가 진입 목록(FE-28): 주문 상세는 주문 목록·클레임 목록 양쪽에서 진입하므로 back이 클레임 목록(쿼리 포함)이면 그대로
 * 복귀한다. 회원 상세(Track 84)는 일반회원·탈퇴회원 목록 양쪽에서 진입한다. 그 외 화면(상품 등)은 기존처럼 base만 허용한다.
 */
const EXTRA_BACK_BASES: Record<string, string[]> = {
  // 대시보드(FE-33) 최근 주문·상위 상품에서 주문·상품 상세로 진입한다.
  [ADMIN_ORDERS_PATH]: [ADMIN_CLAIMS_PATH, ADMIN_DASHBOARD_PATH],
  [ADMIN_PRODUCTS_PATH]: [ADMIN_DASHBOARD_PATH],
  [ADMIN_MEMBERS_PATH]: [ADMIN_MEMBERS_WITHDRAWN_PATH],
  // 정산 상세(Track 85)는 정산 내역·셀러별 정산 양쪽에서 진입한다.
  [ADMIN_SETTLEMENTS_PATH]: [ADMIN_SETTLEMENTS_SELLERS_PATH],
}

/**
 * 허용 prefix(Track 84): 주문 상세는 회원 상세(/admin/members/usr_…?tab=…)에서도 진입하므로 그 하위 경로 전체를 back으로 허용한다.
 * base 정확·"base?" 매칭이 아닌 "prefix/" 매칭이라 별도 표로 둔다.
 */
const EXTRA_BACK_PREFIXES: Record<string, string[]> = {
  // 주문 상세는 정산 상세 품목(/admin/settlements/{id}?tab=…·Track 85)에서도 진입한다.
  [ADMIN_ORDERS_PATH]: [`${ADMIN_MEMBERS_PATH}/`, `${ADMIN_SETTLEMENTS_PATH}/`],
  // 회원 상세는 셀러 상세 구성원(/admin/members/sellers/slr_…·FE-40)에서도 진입한다.
  [ADMIN_MEMBERS_PATH]: [`${ADMIN_SELLERS_PATH}/`],
}

function matchesBase(value: string, base: string): boolean {
  return value === base || value.startsWith(`${base}?`)
}

export function resolveBackPath(back: unknown, base: string = ADMIN_PRODUCTS_PATH): string {
  const value = Array.isArray(back) ? back[0] : back
  if (typeof value !== 'string') return base
  if (matchesBase(value, base)) return value
  if ((EXTRA_BACK_BASES[base] ?? []).some((extra) => matchesBase(value, extra))) return value
  if ((EXTRA_BACK_PREFIXES[base] ?? []).some((prefix) => value.startsWith(prefix))) return value
  return base
}
