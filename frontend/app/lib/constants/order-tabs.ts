/**
 * 주문내역 탭 단일 소스(FE-63·Track 101-B). /orders 한 라우트에서 주문·취소·반품·교환을 탭으로 보여주며,
 * 탭 3종은 BE ClaimType 3값과 1:1이다(lib/constants/claim.ts ClaimType 유니온 재사용·매직 문자열 금지).
 *
 * URL(?tab=&page=)이 탭·페이지의 단일 소스다 — 컴포넌트 로컬 ref를 진실로 두지 않는다(ProductListView의 sort 동기화 선례).
 */

import type { ClaimType } from '~/lib/constants/claim'

/** 주문내역 탭 code. 'order'는 주문 목록, 나머지 3종은 유형별 클레임 목록이다. */
export type OrderListTab = 'order' | 'cancel' | 'return' | 'exchange'

/** 기본 탭(?tab 미지정·허용값 밖일 때). */
export const DEFAULT_ORDER_LIST_TAB: OrderListTab = 'order'

/** 탭 code→한글 라벨. */
export const ORDER_LIST_TAB_LABELS: Record<OrderListTab, string> = {
  order: '주문내역',
  cancel: '취소',
  return: '반품',
  exchange: '교환',
}

/** 탭 표시 순서(주문 → 취소 → 반품 → 교환). */
export const ORDER_LIST_TABS: OrderListTab[] = ['order', 'cancel', 'return', 'exchange']

/** 클레임 탭 code→BE ClaimType. 주문 탭('order')은 클레임 조회를 하지 않으므로 항목이 없다. */
const CLAIM_TYPE_BY_TAB: Record<Exclude<OrderListTab, 'order'>, ClaimType> = {
  cancel: 'CANCEL',
  return: 'RETURN',
  exchange: 'EXCHANGE',
}

/** 탭 code→BE ClaimType. 주문 탭이면 null(= 클레임 조회 대상 아님). */
export function claimTypeOfTab(tab: OrderListTab): ClaimType | null {
  return tab === 'order' ? null : CLAIM_TYPE_BY_TAB[tab]
}

/** BE ClaimType→탭 code. 클레임 상세의 목록 복귀 링크가 자기 유형 탭으로 돌아갈 때 쓴다. */
export function tabOfClaimType(claimType: ClaimType): OrderListTab {
  if (claimType === 'RETURN') return 'return'
  if (claimType === 'EXCHANGE') return 'exchange'
  return 'cancel'
}

/** ?tab= 쿼리를 탭으로 해석한다. 배열·허용값 밖·미지정은 전부 기본 탭으로 떨어진다(방어). */
export function parseOrderListTab(raw: unknown): OrderListTab {
  if (typeof raw !== 'string') return DEFAULT_ORDER_LIST_TAB
  return (ORDER_LIST_TABS as string[]).includes(raw) ? (raw as OrderListTab) : DEFAULT_ORDER_LIST_TAB
}

/** ?page= 쿼리를 0-based 페이지로 해석한다. 숫자가 아니거나 음수면 0(방어·/products의 categoryId 파싱 선례). */
export function parseOrderListPage(raw: unknown): number {
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return 0
  return Number(raw)
}
