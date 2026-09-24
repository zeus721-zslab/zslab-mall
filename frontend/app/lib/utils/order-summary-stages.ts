import type { OrderStatusStages } from '~/types/order'
import type { OrderItemStatusFilter } from '~/lib/constants/order-tabs'

/** 마이페이지 주문 현황 한 단계(FE-72). dimmed = 0건이라 흐리게 표시한다. */
export interface OrderSummaryStage {
  key: keyof OrderStatusStages
  label: string
  count: number
  dimmed: boolean
}

/** 표시 순서·라벨(주문 흐름 순서). 키는 BE stages 필드와 1:1이다(D-223). */
const ORDER_SUMMARY_STAGE_LABELS: { key: keyof OrderStatusStages; label: string }[] = [
  { key: 'paid', label: '결제 완료' },
  { key: 'preparing', label: '상품 준비' },
  { key: 'shipping', label: '배송 중' },
  { key: 'delivered', label: '배송 완료' },
  { key: 'confirmed', label: '구매 확정' },
]

/** 단계가 세는 품목 상태(D-224 주문 목록 필터 값과 같다). */
const ORDER_SUMMARY_STAGE_ITEM_STATUS: Record<keyof OrderStatusStages, OrderItemStatusFilter> = {
  paid: 'PAID',
  preparing: 'PREPARING',
  shipping: 'SHIPPING',
  delivered: 'DELIVERED',
  confirmed: 'CONFIRMED',
}

/** 요약 응답의 단계별 건수를 표시 순서의 5단계로 바꾼다. */
export function toOrderSummaryStages(stages: OrderStatusStages): OrderSummaryStage[] {
  return ORDER_SUMMARY_STAGE_LABELS.map(({ key, label }) => ({
    key,
    label,
    count: stages[key],
    dimmed: stages[key] === 0,
  }))
}

/**
 * 현황 숫자가 여는 주문 목록(FE-80·D-224): 그 단계 품목이 있는 최근 주문만. 0건 단계도 같은 링크다(빈 목록 안내로 이동).
 * 필터는 전체 주문 탭에만 걸리므로 tab은 붙이지 않는다(기본 탭).
 */
export function orderSummaryStageLink(key: keyof OrderStatusStages): { path: string; query: { itemStatus: OrderItemStatusFilter } } {
  return { path: '/orders', query: { itemStatus: ORDER_SUMMARY_STAGE_ITEM_STATUS[key] } }
}

/** 필터 값 → 현황과 같은 단계 라벨(주문 목록 필터 칩·빈 결과 문구). */
export function orderSummaryStageLabel(itemStatus: OrderItemStatusFilter): string {
  const stage = ORDER_SUMMARY_STAGE_LABELS.find(({ key }) => ORDER_SUMMARY_STAGE_ITEM_STATUS[key] === itemStatus)
  return stage === undefined ? itemStatus : stage.label
}
