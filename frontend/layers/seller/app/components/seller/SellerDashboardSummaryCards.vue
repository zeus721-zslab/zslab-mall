<script setup lang="ts">
import { mdiCashMultiple, mdiCashRefund, mdiCartOutline, mdiChartLineVariant } from '@mdi/js'
import type { SellerDashboardSummary } from '#layers/seller/app/types/seller-dashboard'
import { formatCount, formatWon } from '#layers/seller/app/lib/format'

/**
 * 기간 요약 카드 4장(Track 90-B-3·D-192): 매출·환불·순매출·주문 건수. 전부 자기 품목(order_item) 축이며 비교 기간(증감률)은 없다(단일 기간).
 * "주문 건수"는 자기 품목이 포함된 주문 수(COUNT DISTINCT order)라 주문 목록의 품목 행 수와 다르다 — 카드 캡션에 그 의미를 적는다.
 * 로딩 중(summary null)에는 값 자리에 —.
 */
const props = defineProps<{
  summary: SellerDashboardSummary | null
}>()

interface CardSpec {
  key: keyof SellerDashboardSummary
  label: string
  icon: string
  color: 'primary' | 'success' | 'info' | 'warning'
  format: (value: number) => string
  caption: string
}

const CARD_SPECS: CardSpec[] = [
  { key: 'revenue', label: '매출', icon: mdiCashMultiple, color: 'primary', format: formatWon, caption: '결제완료 주문의 내 품목 합계' },
  { key: 'refund', label: '환불', icon: mdiCashRefund, color: 'warning', format: formatWon, caption: '내 품목 클레임의 환불 완료액' },
  { key: 'netRevenue', label: '순매출', icon: mdiChartLineVariant, color: 'success', format: formatWon, caption: '매출 − 환불' },
  { key: 'orderCount', label: '주문 건수', icon: mdiCartOutline, color: 'info', format: (value) => formatCount(value), caption: '내 품목이 포함된 주문 수(품목 수와 다를 수 있음)' },
]

const cards = computed(() =>
  CARD_SPECS.map((card) => ({
    testId: `dashboard-card-${card.key}`,
    label: card.label,
    icon: card.icon,
    color: card.color,
    value: props.summary ? card.format(props.summary[card.key]) : '—',
    caption: card.caption,
  })),
)
</script>

<template>
  <v-row dense class="mb-4" data-testid="dashboard-summary">
    <v-col v-for="card in cards" :key="card.testId" cols="12" sm="6" lg="3">
      <div class="h-100" :data-testid="card.testId">
        <SellerStatCard :label="card.label" :value="card.value" :caption="card.caption" :icon="card.icon" :color="card.color" class="h-100" />
      </div>
    </v-col>
  </v-row>
</template>
