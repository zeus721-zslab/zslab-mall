import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { InboxApiParams } from '~/types/inbox'
import { type InboxAudience, type InboxItemType, type InboxTab, isInboxTab, isInboxTypeOf } from '~/lib/constants/inbox'

/**
 * 운영 인박스 화면 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(D-248 · admin-inquiry-query 관례). 관리자·셀러 공용이며 역할(audience)로 유형을
 * 검증한다(다른 역할 유형은 BE 400이라 URL에서 걸러 전체로 되돌린다). 기본값(오늘 탭 · 전체 유형 · 선택 없음)은 URL에서 뺀다.
 *
 * 선택 항목은 "유형:ref" 키로 둔다 — ref는 유형마다 다른 식별 체계(정산·불일치 id가 겹칠 수 있음)라 유형과 함께여야 유일하다.
 */
export interface InboxQueryState {
  tab: InboxTab
  type: InboxItemType | null
  selected: string | null
}

export const DEFAULT_INBOX_QUERY: InboxQueryState = { tab: 'TODAY', type: null, selected: null }

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

export function parseInboxQuery(query: LocationQuery, audience: InboxAudience): InboxQueryState {
  const tab = first(query.tab)
  const type = first(query.type)
  return {
    tab: isInboxTab(tab) ? tab : DEFAULT_INBOX_QUERY.tab,
    type: isInboxTypeOf(audience, type) ? type : null,
    selected: first(query.selected),
  }
}

export function toInboxRouteQuery(state: InboxQueryState): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.tab !== DEFAULT_INBOX_QUERY.tab) query.tab = state.tab
  if (state.type) query.type = state.type
  if (state.selected) query.selected = state.selected
  return query
}

export function toInboxApiParams(state: InboxQueryState): InboxApiParams {
  const params: InboxApiParams = { tab: state.tab }
  if (state.type) params.type = state.type
  return params
}

/** 선택 키(유형:ref). 클레임 후속 ref 안의 ':'는 첫 구분자 뒤라 그대로 보존된다. */
export function inboxItemKey(item: { type: InboxItemType; ref: string }): string {
  return `${item.type}:${item.ref}`
}
