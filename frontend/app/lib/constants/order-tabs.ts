/**
 * 주문내역 탭 단일 소스(FE-63·Track 101-B → FE-73 2탭 통합). /orders 한 라우트에서 전체 주문과 취소·반품·교환을 탭으로 보여준다.
 * 취소·반품·교환 탭은 클레임 목록을 유형 구분 없이 조회한다(BE type 파라미터 미전송).
 *
 * URL(?tab=&page=)이 탭·페이지의 단일 소스다 — 컴포넌트 로컬 ref를 진실로 두지 않는다(ProductListView의 sort 동기화 선례).
 */

import type { ClaimType, OrderItemStatusCode } from '~/lib/constants/claim'

/** 주문내역 탭 code. 'order'는 주문 목록, 'claim'은 클레임 목록(취소·반품·교환 전체)이다. */
export type OrderListTab = 'order' | 'claim'

/** 기본 탭(?tab 미지정·허용값 밖일 때). */
export const DEFAULT_ORDER_LIST_TAB: OrderListTab = 'order'

/** 탭 code→한글 라벨. */
export const ORDER_LIST_TAB_LABELS: Record<OrderListTab, string> = {
  order: '전체 주문',
  claim: '취소·반품·교환',
}

/** 탭 표시 순서(전체 주문 → 취소·반품·교환). */
export const ORDER_LIST_TABS: OrderListTab[] = ['order', 'claim']

/**
 * 취소·반품·교환 탭의 유형 필터 URL 값(?type=·FE-73 보완 1) ↔ BE ClaimType. 통합 전(FE-63) 유형별 탭 값과 같은 문자열이라
 * 옛 ?tab=cancel|return|exchange 링크도 claim 탭 + 해당 유형으로 해석한다(링크 의미 보존).
 */
export const CLAIM_TYPE_QUERY_VALUES: Record<ClaimType, string> = {
  CANCEL: 'cancel',
  RETURN: 'return',
  EXCHANGE: 'exchange',
}

/** 필터 칩 순서(null = 전체). */
export const CLAIM_TYPE_FILTERS: (ClaimType | null)[] = [null, 'CANCEL', 'RETURN', 'EXCHANGE']

const CLAIM_TYPE_BY_QUERY_VALUE = new Map<string, ClaimType>([
  ['cancel', 'CANCEL'],
  ['return', 'RETURN'],
  ['exchange', 'EXCHANGE'],
])

/** 통합 전(FE-63) 유형별 탭 값. 저장된 링크·북마크 호환을 위해 claim 탭으로 해석한다. */
const LEGACY_CLAIM_TABS = [...CLAIM_TYPE_BY_QUERY_VALUE.keys()]

/** 옛 유형 탭 값(?tab=cancel 등)인지. 페이지가 URL을 새 형식(tab=claim&type=)으로 정리할 때 쓴다. */
export function isLegacyClaimTab(raw: unknown): boolean {
  return typeof raw === 'string' && LEGACY_CLAIM_TABS.includes(raw)
}

/**
 * 클레임 유형 필터 해석(null = 전체). ?type= 허용값이 우선이고, 없거나 허용값 밖이면 옛 ?tab=cancel|return|exchange에서 유형을 읽는다.
 * 둘 다 아니면 전체(방어).
 */
export function parseClaimTypeFilter(rawType: unknown, rawTab: unknown): ClaimType | null {
  const fromType = typeof rawType === 'string' ? CLAIM_TYPE_BY_QUERY_VALUE.get(rawType) : undefined
  if (fromType) return fromType
  const fromLegacyTab = typeof rawTab === 'string' ? CLAIM_TYPE_BY_QUERY_VALUE.get(rawTab) : undefined
  return fromLegacyTab ?? null
}

/** BE ClaimType→탭 code. 클레임은 유형과 무관하게 한 탭이다(클레임 상세 복귀·주문 카드 진행 클레임 배지). */
export function tabOfClaimType(claimType: ClaimType): OrderListTab {
  void claimType
  return 'claim'
}

/** ?tab= 쿼리를 탭으로 해석한다. 옛 유형 탭 값은 claim, 배열·허용값 밖·미지정은 기본 탭으로 떨어진다(방어). */
export function parseOrderListTab(raw: unknown): OrderListTab {
  if (typeof raw !== 'string') return DEFAULT_ORDER_LIST_TAB
  if (LEGACY_CLAIM_TABS.includes(raw)) return 'claim'
  return (ORDER_LIST_TABS as string[]).includes(raw) ? (raw as OrderListTab) : DEFAULT_ORDER_LIST_TAB
}

/**
 * 전체 주문 탭의 품목 상태 필터 값(?itemStatus=·D-224·FE-80). 마이페이지 홈 주문 현황 5단계와 같은 품목 상태이며 BE도 이 5값만 받는다
 * (그 밖의 값은 400). URL 값은 BE와 같은 대문자다.
 */
export type OrderItemStatusFilter = Extract<OrderItemStatusCode, 'PAID' | 'PREPARING' | 'SHIPPING' | 'DELIVERED' | 'CONFIRMED'>

/** 필터 허용값(주문 흐름 순서). */
export const ORDER_ITEM_STATUS_FILTERS: OrderItemStatusFilter[] = ['PAID', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED']

/** 필터가 있을 때 BE가 거는 주문일 기간(BuyerOrderQueryService.SUMMARY_PERIOD_MONTHS = 3 · 요약과 같은 값 · BE 변경 시 함께 갱신). */
export const ITEM_STATUS_FILTER_PERIOD_MONTHS = 3

/** ?itemStatus= 쿼리를 필터 값으로 해석한다. 허용 5값 밖·소문자·배열·미지정은 null(필터 없음 — BE 400을 부르지 않도록 보내지 않는다). */
export function parseItemStatusFilter(raw: unknown): OrderItemStatusFilter | null {
  if (typeof raw !== 'string') return null
  return (ORDER_ITEM_STATUS_FILTERS as string[]).includes(raw) ? (raw as OrderItemStatusFilter) : null
}

/** ?page= 쿼리를 0-based 페이지로 해석한다. 숫자가 아니거나 음수면 0(방어·/products의 categoryId 파싱 선례). */
export function parseOrderListPage(raw: unknown): number {
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return 0
  return Number(raw)
}
