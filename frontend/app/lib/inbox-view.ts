import type { InboxItemResponse, InboxTypeCount } from '~/types/inbox'
import {
  type InboxAudience,
  type InboxItemType,
  type InboxTab,
  type InboxTargetKey,
  inboxItemTypeLabel,
} from '~/lib/constants/inbox'
import { inboxItemKey } from '~/lib/inbox-query'

/**
 * 운영 인박스 표시 규칙 순수 함수(D-248 · 관리자·셀러 공용 · 레이어 CSS·라우트·composable에 묶이지 않는 것만). 응답 정규화 · 기한 표시 ·
 * 처리 후 다음 선택 · 빈 상태 문구 · 패널 처리 가능 여부를 모아 vitest로 고정한다. 시각은 BE가 붙인 +09:00 ISO 문자열이며 Date로 읽어 절대 시각을
 * 비교한다(브라우저 시간대와 무관).
 */

/** 화면용 인박스 항목. 기한·기준 시각 부재는 null(기한 없음). */
export interface InboxItem {
  key: string
  type: InboxItemType
  ref: string
  /** 이동 키: ref의 ':' 앞부분(클레임 후속 = 클레임 publicId · 그 외 = ref 그대로). */
  sourceRef: string
  /** 클레임 후속 단계 코드(ref의 ':' 뒷부분 · 그 외 null). */
  step: string | null
  title: string
  subtitle: string | null
  baseAt: string | null
  dueAt: string | null
  overdue: boolean
  targetKey: InboxTargetKey
}

export function normalizeInboxItem(response: InboxItemResponse): InboxItem {
  const separator = response.ref.indexOf(':')
  return {
    key: inboxItemKey(response),
    type: response.type,
    ref: response.ref,
    sourceRef: separator < 0 ? response.ref : response.ref.slice(0, separator),
    step: separator < 0 ? null : response.ref.slice(separator + 1),
    title: response.title ?? inboxItemTypeLabel(response.type),
    subtitle: response.subtitle ?? null,
    baseAt: response.baseAt ?? null,
    dueAt: response.dueAt ?? null,
    overdue: response.overdue,
    targetKey: response.targetKey,
  }
}

// ---------- 기한 표시 ----------

const MINUTE_MS = 60 * 1000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS
const KST_OFFSET_MS = 9 * HOUR_MS

/** 이 시간 안에 기한이 오면 "임박". 오늘 탭 전체를 임박으로 칠하지 않도록 짧게 둔다. */
export const INBOX_IMMINENT_MS = 3 * HOUR_MS

export type InboxDeadlineTone = 'overdue' | 'imminent' | 'normal' | 'none'

export interface InboxDeadline {
  tone: InboxDeadlineTone
  /** "3시간 남음" · "2일 지남" · "기한 없음". */
  text: string
}

/** 두 시각의 차이를 분·시간·일 중 가장 큰 단위로(내림 · 최소 1분). */
export function formatDuration(ms: number): string {
  const absolute = Math.abs(ms)
  if (absolute < HOUR_MS) return `${Math.max(1, Math.floor(absolute / MINUTE_MS))}분`
  if (absolute < DAY_MS) return `${Math.floor(absolute / HOUR_MS)}시간`
  return `${Math.floor(absolute / DAY_MS)}일`
}

export function inboxDeadline(dueAt: string | null, nowMs: number): InboxDeadline {
  if (dueAt === null) return { tone: 'none', text: '기한 없음' }
  const dueMs = Date.parse(dueAt)
  if (Number.isNaN(dueMs)) return { tone: 'none', text: '기한 없음' }
  const remaining = dueMs - nowMs
  if (remaining < 0) return { tone: 'overdue', text: `${formatDuration(remaining)} 지남` }
  return { tone: remaining <= INBOX_IMMINENT_MS ? 'imminent' : 'normal', text: `${formatDuration(remaining)} 남음` }
}

/** 다음 KST 자정까지 남은 시간(ms). 오늘·예정 탭 경계가 자정에 바뀌므로 그때 다시 읽는다. */
export function msUntilNextKstMidnight(nowMs: number): number {
  const kstNow = nowMs + KST_OFFSET_MS
  const nextKstMidnight = (Math.floor(kstNow / DAY_MS) + 1) * DAY_MS
  return nextKstMidnight - kstNow
}

// ---------- 목록 ----------

/** 오늘 탭 전체 건수(메뉴 배지). 보류 항목은 BE가 이미 뺀 값이다. */
export function inboxTotal(counts: InboxTypeCount[]): number {
  return counts.reduce((sum, count) => sum + count.count, 0)
}

/**
 * 처리 후 다음 선택 키. 처리한 항목이 아직 남아 있으면 그대로, 사라졌으면 이전 목록에서 그 뒤에 있던 항목 중 남은 첫 항목 → 없으면 앞쪽에서
 * 가장 가까운 항목 → 없으면 현재 첫 항목. 목록이 비면 null.
 */
export function nextInboxSelection(previousKeys: string[], currentKeys: string[], processedKey: string | null): string | null {
  if (currentKeys.length === 0) return null
  if (processedKey === null) return currentKeys[0] ?? null
  if (currentKeys.includes(processedKey)) return processedKey
  const remaining = new Set(currentKeys)
  const index = previousKeys.indexOf(processedKey)
  if (index >= 0) {
    const after = previousKeys.slice(index + 1).find((key) => remaining.has(key))
    if (after) return after
    const before = previousKeys.slice(0, index).reverse().find((key) => remaining.has(key))
    if (before) return before
  }
  return currentKeys[0] ?? null
}

export function inboxEmptyMessage(tab: InboxTab, type: InboxItemType | null): string {
  const base = tab === 'TODAY' ? '오늘 처리할 일이 없습니다.' : '예정된 처리 대기가 없습니다.'
  return type === null ? base : `${inboxItemTypeLabel(type)} — ${base}`
}

export const INBOX_TRUNCATED_MESSAGE = '항목이 많아 일부만 표시합니다. 유형을 골라 좁혀 보세요.'

// ---------- 상세 패널 처리 ----------

/** 상세 패널에서 바로 처리하는 동작(P1b-1 범위 · 그 외 유형은 원래 화면으로 이동). */
export type InboxPanelAction = 'INQUIRY_ANSWER' | 'SELLER_STATUS' | 'SHIPMENT' | 'QUESTION_ANSWER'

const PANEL_ACTIONS: Record<InboxAudience, Partial<Record<InboxItemType, InboxPanelAction>>> = {
  ADMIN: { INQUIRY_UNANSWERED: 'INQUIRY_ANSWER', SELLER_REVIEW: 'SELLER_STATUS' },
  SELLER: { DELIVERY_READY: 'SHIPMENT', QUESTION_UNANSWERED: 'QUESTION_ANSWER' },
}

export function inboxPanelAction(audience: InboxAudience, type: InboxItemType): InboxPanelAction | null {
  return PANEL_ACTIONS[audience][type] ?? null
}
