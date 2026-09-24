import type { OrderItemStatusCode } from '~/lib/constants/claim'
import type { RenewBadgeTone } from './components/RenewBadge.vue'

/**
 * renew 품목 상태 배지 tone(FE-80·한 곳). 주문 목록 카드·주문 상세 품목 행이 같이 쓴다. 라벨은 ORDER_ITEM_STATUS_LABELS 그대로다.
 * 정상 흐름은 결제 완료 info → 준비·배송 neutral → 배송 완료 success(구매 확정을 기다림) → 구매 확정 neutral(끝난 상태)이다.
 * 클레임 계열은 관리자·셀러 품목 색 규칙(ADMIN_ORDER_ITEM_STATUS_SEMANTIC)의 의미를 따른다: 요청 warning · 취소·반품 완료 danger · 교환 완료 info.
 */
export const ORDER_ITEM_STATUS_TONE: Record<OrderItemStatusCode, RenewBadgeTone> = {
  ORDERED: 'info',
  PAID: 'info',
  PREPARING: 'neutral',
  SHIPPING: 'neutral',
  DELIVERED: 'success',
  CONFIRMED: 'neutral',
  CANCEL_REQUESTED: 'warning',
  CANCELLED: 'danger',
  RETURN_REQUESTED: 'warning',
  RETURNED: 'danger',
  EXCHANGE_REQUESTED: 'warning',
  EXCHANGED: 'info',
}

/** 품목 상태 code → 배지 tone. 매핑에 없는 code(BE 신규 값)는 neutral(방어). */
export function orderItemStatusTone(code: string): RenewBadgeTone {
  return ORDER_ITEM_STATUS_TONE[code as OrderItemStatusCode] ?? 'neutral'
}
