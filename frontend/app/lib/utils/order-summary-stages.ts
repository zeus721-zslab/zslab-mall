import type { OrderStatusStages } from '~/types/order'

/** 마이페이지 주문 현황 한 단계(FE-72). dimmed = 0건이라 흐리게 표시한다(표시 전용·클릭 없음). */
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

/** 요약 응답의 단계별 건수를 표시 순서의 5단계로 바꾼다. */
export function toOrderSummaryStages(stages: OrderStatusStages): OrderSummaryStage[] {
  return ORDER_SUMMARY_STAGE_LABELS.map(({ key, label }) => ({
    key,
    label,
    count: stages[key],
    dimmed: stages[key] === 0,
  }))
}
