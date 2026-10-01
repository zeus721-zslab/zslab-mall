import type { InboxAudience } from '~/lib/constants/inbox'
import type { InboxItem } from '~/lib/inbox-view'

/**
 * 인박스 항목 → 기존 처리 화면 경로(D-248 "원래 화면에서 열기" · recon-report-p1b §4). 관리자·셀러 레이어 경로 상수를 app/lib에서 import할 수 없어
 * (레이어 격리) 경로 문자열을 여기 둔다 — 레이어 admin-back-path·seller-back-path와 같은 값이며 vitest가 고정한다.
 *
 * 단건 상세 화면이 있는 유형(셀러 심사·정산·셀러 주문 품목)은 상세로, 없는 유형은 목록 + 필터(주문번호·상품명 검색어)로 보낸다.
 * 클레임 후속은 단계 코드를 목록의 "필요 액션" 필터로 바꿔 보낸다(클레임 목록 검색어는 클레임 publicId를 받지 않아 부제의 주문번호를 쓴다).
 */

/** 목록 검색어 최대 길이(BE keyword 50자 초과 400 · ADMIN_ORDER_KEYWORD_MAX·SELLER_*_KEYWORD_MAX와 같은 값). */
export const INBOX_TARGET_KEYWORD_MAX = 50

/** 클레임 후속 단계 코드(BE ClaimFollowupInboxSource) → 관리자 클레임 목록 action 필터(AdminClaimActionFilter). */
export const CLAIM_FOLLOWUP_STEP_ACTIONS: Record<string, string> = {
  PICKUP: 'CONFIRM_PICKUP',
  INSPECT: 'INSPECT',
  EXCH_SHIP: 'REGISTER_EXCHANGE_SHIPMENT',
  EXCH_DLVD: 'MARK_EXCHANGE_DELIVERED',
  REFUND: 'INITIATE_REFUND',
}
const FOLLOWUP_ACTION_FALLBACK = 'FOLLOWUP'

export interface InboxTargetRoute {
  path: string
  query: Record<string, string>
}

function keywordOf(value: string | null): Record<string, string> {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' ? {} : { keyword: trimmed.slice(0, INBOX_TARGET_KEYWORD_MAX) }
}

function adminTarget(item: InboxItem): InboxTargetRoute {
  switch (item.type) {
    case 'CLAIM_REQUESTED':
      return { path: '/admin/orders/claims', query: { status: 'REQUESTED', ...keywordOf(item.subtitle) } }
    case 'CLAIM_FOLLOWUP':
      return {
        path: '/admin/orders/claims',
        query: { action: CLAIM_FOLLOWUP_STEP_ACTIONS[item.step ?? ''] ?? FOLLOWUP_ACTION_FALLBACK, ...keywordOf(item.subtitle) },
      }
    case 'LONG_SHIPPING':
      return { path: '/admin/orders/deliveries', query: { status: 'SHIPPING', ...keywordOf(item.subtitle) } }
    case 'INQUIRY_UNANSWERED':
      return { path: '/admin/inquiries', query: {} }
    case 'SELLER_REVIEW':
      return { path: `/admin/members/sellers/${item.sourceRef}`, query: {} }
    case 'PRODUCT_APPROVAL':
      return { path: '/admin/products', query: { status: 'PENDING', ...keywordOf(item.title) } }
    case 'SETTLEMENT_CONFIRM':
    case 'SETTLEMENT_PAYOUT':
      return { path: `/admin/settlements/${item.sourceRef}`, query: {} }
    case 'RECONCILIATION_OPEN':
      return { path: '/admin/orders/reconciliation', query: { status: 'OPEN', type: item.title } }
    default:
      return { path: '/admin', query: {} }
  }
}

function sellerTarget(item: InboxItem): InboxTargetRoute {
  switch (item.type) {
    case 'DELIVERY_READY':
      return { path: `/seller/orders/${item.sourceRef}`, query: {} }
    case 'QUESTION_UNANSWERED':
      return { path: '/seller/products/questions', query: {} }
    case 'LONG_SHIPPING':
      return { path: '/seller/deliveries', query: { status: 'SHIPPING', ...keywordOf(item.subtitle) } }
    case 'LOW_STOCK':
      return { path: '/seller/products/inventory', query: keywordOf(item.title) }
    default:
      return { path: '/seller', query: {} }
  }
}

export function inboxTargetRoute(audience: InboxAudience, item: InboxItem): InboxTargetRoute {
  return audience === 'ADMIN' ? adminTarget(item) : sellerTarget(item)
}
