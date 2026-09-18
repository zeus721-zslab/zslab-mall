<script setup lang="ts">
import { mdiCashMultiple, mdiCashRefund, mdiCartOutline, mdiChartLine, mdiPackageVariantClosed, mdiReceiptText } from '@mdi/js'
import type { AdminSalesSummary } from '#layers/admin/app/types/admin-sales-stats'
import { STATS_COMPARE_LABELS, type StatsCompare } from '#layers/admin/app/lib/constants/admin-sales-stats'
import { salesSummaryCards, type SalesSummaryKey } from '#layers/admin/app/lib/admin-sales-stats-view'

/**
 * 매출 통계 요약 카드 6장(FE-34): 매출·환불·순매출·주문수·객단가·주문당 품목수. 비교 기간이 있으면 증감 배지 + 비교값 캡션,
 * 없으면 배지 "—"·회색(AdminDashboardSummaryCards 패턴). 환불 카드는 증가가 부정이라 톤이 반전된다(lib salesChangeTone).
 */
const props = defineProps<{
  summary: AdminSalesSummary | null
  compareSummary: AdminSalesSummary | null
  compare: StatsCompare
}>()

const ICONS: Record<SalesSummaryKey, string> = {
  revenue: mdiCashMultiple,
  refund: mdiCashRefund,
  netRevenue: mdiChartLine,
  orderCount: mdiCartOutline,
  itemQuantity: mdiPackageVariantClosed,
  avgOrderValue: mdiReceiptText,
  avgItemsPerOrder: mdiPackageVariantClosed,
}

const COLORS: Record<SalesSummaryKey, 'primary' | 'success' | 'info' | 'warning'> = {
  revenue: 'primary',
  refund: 'warning',
  netRevenue: 'success',
  orderCount: 'info',
  itemQuantity: 'info',
  avgOrderValue: 'primary',
  avgItemsPerOrder: 'info',
}

// AdminStatCard는 caption이 falsy면 줄을 렌더하지 않아 높이가 어긋난다 → 비교 없으면 NBSP로 줄을 차지시킨다(FE-33 선례).
const EMPTY_CAPTION = ' '

const cards = computed(() => salesSummaryCards(props.summary, props.compareSummary))
const compareLabel = computed(() => (props.compare === 'NONE' ? '비교 없음' : `${STATS_COMPARE_LABELS[props.compare]} 대비`))
</script>

<template>
  <v-row dense class="mb-4" data-testid="sales-summary">
    <v-col v-for="card in cards" :key="card.key" cols="12" sm="6" lg="4">
      <div class="h-100" :data-testid="`sales-card-${card.key}`">
        <AdminStatCard
          :label="card.label"
          :value="card.value"
          :caption="card.compareValue ? `비교 기간 ${card.compareValue}` : EMPTY_CAPTION"
          :icon="ICONS[card.key]"
          :color="COLORS[card.key]"
          class="h-100"
        >
          <v-chip :class="card.rateClass" size="x-small" variant="flat" :title="compareLabel" data-testid="sales-card-rate">{{ card.rateText }}</v-chip>
          <span class="text-caption text-medium-emphasis ml-1">{{ compareLabel }}</span>
        </AdminStatCard>
      </div>
    </v-col>
  </v-row>
</template>
