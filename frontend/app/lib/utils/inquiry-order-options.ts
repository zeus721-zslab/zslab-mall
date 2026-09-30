import type { InquiryOrderOption } from '~/types/inquiry'
import type { OrderSummary } from '~/types/order'
import { formatDateTime } from '~/lib/utils/datetime'

/** 최근 주문 목록에 없는 주문을 ?order로 받았을 때의 선택지 이름(주문 상세에서 들어온 오래된 주문). */
export const ATTACHED_ORDER_FALLBACK_LABEL = '주문 상세에서 선택한 주문'

/** 선택지 이름: 주문번호 · 대표 상품 · 주문일(주문번호가 없는 옛 응답이면 생략). */
export function inquiryOrderOptionLabel(order: OrderSummary): string {
  const parts = [order.orderNo, order.previewTitle, formatDateTime(order.orderedAt).slice(0, 10)].filter(
    (part): part is string => typeof part === 'string' && part !== '',
  )
  return parts.join(' · ')
}

/**
 * 작성 화면의 첨부 주문 선택지(Track 106-4). 최근 주문 순서를 그대로 쓰고, ?order로 받은 주문이 그 안에 없으면 맨 앞에 넣는다 — 주문 상세에서
 * 들어온 문의는 그 주문이 최근 목록 밖이어도 첨부돼야 한다(소유 판정은 BE가 등록 때 한다).
 */
export function buildInquiryOrderOptions(orders: OrderSummary[], attachedOrderId: string | null): InquiryOrderOption[] {
  const options = orders.map((order) => ({ orderId: order.orderId, label: inquiryOrderOptionLabel(order) }))
  if (attachedOrderId && !options.some((option) => option.orderId === attachedOrderId)) {
    return [{ orderId: attachedOrderId, label: ATTACHED_ORDER_FALLBACK_LABEL }, ...options]
  }
  return options
}
