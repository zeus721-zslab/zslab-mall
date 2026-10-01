import type { ClaimSuggestion, ClaimType } from '~/lib/constants/claim'
import type { InboxItemType, InboxTab, InboxTargetKey } from '~/lib/constants/inbox'

/**
 * 운영 인박스 API 타입(D-248 · BE InboxResponse·InboxItemResponse·InboxSnoozeRequest 1:1). NON_NULL 직렬화라 기한 없는 항목은 dueAt·baseAt이,
 * 값이 없는 표시 문구는 title·subtitle이 응답에서 빠진다(optional).
 */
export interface InboxItemResponse {
  type: InboxItemType
  /** 원천 식별자(publicId · id 문자열 · 클레임 후속은 "클레임 publicId:단계 코드"). 보류 요청에 그대로 쓴다. */
  ref: string
  title?: string
  subtitle?: string
  baseAt?: string
  dueAt?: string
  overdue: boolean
  targetKey: InboxTargetKey
  /** 클레임 유형(클레임 접수만 · D-250). */
  claimType?: ClaimType
  /** 클레임 처리 제안(클레임 접수만 · D-250). */
  suggestion?: ClaimSuggestion
}

export interface InboxTypeCount {
  type: InboxItemType
  count: number
}

export interface InboxResponse {
  items: InboxItemResponse[]
  counts: InboxTypeCount[]
  truncated: boolean
}

export interface InboxApiParams {
  tab: InboxTab
  type?: InboxItemType
}

export interface InboxSnoozeBody {
  type: InboxItemType
  ref: string
  /** ISO offset(+09:00) · 미래만. */
  untilAt: string
  reason: string
}
