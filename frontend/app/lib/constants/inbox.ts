/**
 * 운영 인박스 유형·탭 단일 소스(D-248 · CLAUDE.md 4층위 enum 잠금 (4)프론트). claim.ts 관례(유니온 → 라벨 맵 → 라벨 함수 → is* 가드).
 *
 * 실측 근거: BE InboxItemType(13값 · DB inbox_snooze.item_type CHECK와 동일 · V46) · InboxTab(2값). 역할별 유형 목록과 순서는 BE InboxItemType 선언 순서
 * (InboxQueryService가 이 순서로 counts를 내려준다)와 같다. 관리자 10 · 셀러 4(장기 배송중은 공용).
 */

/** 인박스 항목 유형(BE InboxItemType enum 13값). */
export type InboxItemType =
  | 'CLAIM_REQUESTED'
  | 'CLAIM_FOLLOWUP'
  | 'LONG_SHIPPING'
  | 'INQUIRY_UNANSWERED'
  | 'SELLER_REVIEW'
  | 'PRODUCT_APPROVAL'
  | 'SETTLEMENT_CONFIRM'
  | 'SETTLEMENT_PAYOUT'
  | 'RECONCILIATION_OPEN'
  | 'SELLER_DELAY'
  | 'DELIVERY_READY'
  | 'QUESTION_UNANSWERED'
  | 'LOW_STOCK'

export const INBOX_ITEM_TYPES: InboxItemType[] = [
  'CLAIM_REQUESTED',
  'CLAIM_FOLLOWUP',
  'LONG_SHIPPING',
  'INQUIRY_UNANSWERED',
  'SELLER_REVIEW',
  'PRODUCT_APPROVAL',
  'SETTLEMENT_CONFIRM',
  'SETTLEMENT_PAYOUT',
  'RECONCILIATION_OPEN',
  'SELLER_DELAY',
  'DELIVERY_READY',
  'QUESTION_UNANSWERED',
  'LOW_STOCK',
]

export const INBOX_ITEM_TYPE_LABELS: Record<InboxItemType, string> = {
  CLAIM_REQUESTED: '클레임 접수',
  CLAIM_FOLLOWUP: '클레임 후속',
  LONG_SHIPPING: '장기 배송중',
  INQUIRY_UNANSWERED: '1:1 문의',
  SELLER_REVIEW: '셀러 입점 심사',
  PRODUCT_APPROVAL: '상품 승인',
  SETTLEMENT_CONFIRM: '정산 확정',
  SETTLEMENT_PAYOUT: '정산 지급',
  RECONCILIATION_OPEN: '정합성 불일치',
  SELLER_DELAY: '셀러 지연',
  DELIVERY_READY: '발송 대기',
  QUESTION_UNANSWERED: 'Q&A 미답변',
  LOW_STOCK: '재고 임박',
}

/** 인박스 유형 라벨. 매핑에 없는 값은 원본 폴백(방어). */
export function inboxItemTypeLabel(code: string): string {
  return INBOX_ITEM_TYPE_LABELS[code as InboxItemType] ?? code
}

export function isInboxItemType(value: unknown): value is InboxItemType {
  return typeof value === 'string' && (INBOX_ITEM_TYPES as string[]).includes(value)
}

/** 인박스를 보는 역할(BE InboxAudience). */
export type InboxAudience = 'ADMIN' | 'SELLER'

/** 역할별 유형(BE InboxItemType.visibleTo · 선언 순서). */
export const INBOX_AUDIENCE_TYPES: Record<InboxAudience, InboxItemType[]> = {
  ADMIN: [
    'CLAIM_REQUESTED',
    'CLAIM_FOLLOWUP',
    'LONG_SHIPPING',
    'INQUIRY_UNANSWERED',
    'SELLER_REVIEW',
    'PRODUCT_APPROVAL',
    'SETTLEMENT_CONFIRM',
    'SETTLEMENT_PAYOUT',
    'RECONCILIATION_OPEN',
    'SELLER_DELAY',
  ],
  SELLER: ['LONG_SHIPPING', 'DELIVERY_READY', 'QUESTION_UNANSWERED', 'LOW_STOCK'],
}

export function isInboxTypeOf(audience: InboxAudience, value: unknown): value is InboxItemType {
  return isInboxItemType(value) && INBOX_AUDIENCE_TYPES[audience].includes(value)
}

/** 인박스 탭(BE InboxTab 2값). 오늘 = 기한이 오늘(KST) 안·경과 + 기한 없음 / 예정 = 내일 이후. */
export type InboxTab = 'TODAY' | 'UPCOMING'

export const INBOX_TAB_LABELS: Record<InboxTab, string> = {
  TODAY: '오늘',
  UPCOMING: '예정',
}

export function isInboxTab(value: unknown): value is InboxTab {
  return value === 'TODAY' || value === 'UPCOMING'
}

/** 이동 대상 원천 리소스 종류(BE InboxItemType.targetKey 10값). */
export type InboxTargetKey =
  | 'CLAIM'
  | 'DELIVERY'
  | 'INQUIRY'
  | 'SELLER'
  | 'PRODUCT'
  | 'SETTLEMENT'
  | 'RECONCILIATION'
  | 'ORDER_ITEM'
  | 'PRODUCT_QUESTION'
  | 'INVENTORY'

/** 셀러 지연 독촉의 셀러별 결과(BE SellerNudgeResult 5값 · D-252 · 응답 전용). */
export type SellerNudgeResult = 'SENT' | 'FAILED' | 'NO_RECIPIENT' | 'COOLDOWN' | 'NO_DELAY'

export const SELLER_NUDGE_RESULT_LABELS: Record<SellerNudgeResult, string> = {
  SENT: '발송',
  FAILED: '발송 실패',
  NO_RECIPIENT: '연락처 없음',
  COOLDOWN: '24시간 내 독촉함',
  NO_DELAY: '지연 없음',
}

/** 보류 사유 빠른 선택(직접 입력도 가능). BE 사유 최대 200자(InboxSnooze.MAX_REASON_LENGTH). */
export const INBOX_SNOOZE_REASON_PRESETS: string[] = ['외부 확인 대기', '고객 회신 대기']
export const INBOX_SNOOZE_REASON_MAX = 200
