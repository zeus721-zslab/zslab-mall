<script setup lang="ts">
import { mdiAccountPlusOutline, mdiCashMultiple, mdiCartOutline } from '@mdi/js'
import type { AdminDashboardPeriodMetrics, AdminDashboardSummary } from '#layers/admin/app/types/admin-dashboard'
import { changeChipClass, changeRate, changeTone, formatChangeRate } from '#layers/admin/app/lib/admin-dashboard-view'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 요약 카드 6장(FE-33): 오늘/이번 달 × 매출·주문·신규회원. 증감률은 전일·전월 전체 대비(D-180·BE 미계산·FE 산출)를 배지로 붙이고,
 * 매출 카드는 환불·순매출을 캡션으로 병기한다. 로딩 중(summary null)에는 값 자리에 —.
 */
const props = defineProps<{
  summary: AdminDashboardSummary | null
}>()

interface CardSpec {
  key: string
  label: string
  icon: string
  color: 'primary' | 'success' | 'info'
  value: (metrics: AdminDashboardPeriodMetrics) => number
  format: (value: number) => string
  caption?: (metrics: AdminDashboardPeriodMetrics) => string
}

const CARD_SPECS: CardSpec[] = [
  { key: 'revenue', label: '매출', icon: mdiCashMultiple, color: 'primary', value: (m) => m.revenue, format: formatWon,
    caption: (m) => `환불 ${formatWon(m.refund)} · 순매출 ${formatWon(m.netRevenue)}` },
  { key: 'orders', label: '주문', icon: mdiCartOutline, color: 'info', value: (m) => m.orderCount, format: (v) => `${v.toLocaleString('ko-KR')}건` },
  { key: 'members', label: '신규회원', icon: mdiAccountPlusOutline, color: 'success', value: (m) => m.newMemberCount, format: (v) => `${v.toLocaleString('ko-KR')}명` },
]

interface PeriodSpec {
  key: 'today' | 'thisMonth'
  prefix: string
  compareLabel: string
  current: (s: AdminDashboardSummary) => AdminDashboardPeriodMetrics
  previous: (s: AdminDashboardSummary) => AdminDashboardPeriodMetrics
}

const PERIOD_SPECS: PeriodSpec[] = [
  { key: 'today', prefix: '오늘', compareLabel: '전일 대비', current: (s) => s.today, previous: (s) => s.previousDay },
  { key: 'thisMonth', prefix: '이번 달', compareLabel: '전월 대비', current: (s) => s.thisMonth, previous: (s) => s.previousMonth },
]

// AdminStatCard는 caption이 falsy면 줄을 렌더하지 않아 높이가 어긋난다(AdminSettlementTotals 선례) → 캡션 없는 카드는 NBSP로 줄을 차지시킨다.
const EMPTY_CAPTION = ' '

interface CardView {
  testId: string
  label: string
  icon: string
  color: CardSpec['color']
  value: string
  caption: string
  rateText: string
  rateClass: string
  compareLabel: string
}

const cards = computed<CardView[]>(() =>
  PERIOD_SPECS.flatMap((period) =>
    CARD_SPECS.map((card) => {
      const current = props.summary ? period.current(props.summary) : null
      const previous = props.summary ? period.previous(props.summary) : null
      const rate = current && previous ? changeRate(card.value(current), card.value(previous)) : null
      return {
        testId: `dashboard-card-${period.key}-${card.key}`,
        label: `${period.prefix} ${card.label}`,
        icon: card.icon,
        color: card.color,
        value: current ? card.format(card.value(current)) : '—',
        caption: current && card.caption ? card.caption(current) : EMPTY_CAPTION,
        rateText: formatChangeRate(rate),
        rateClass: changeChipClass(changeTone(rate)),
        compareLabel: period.compareLabel,
      }
    }),
  ),
)
</script>

<template>
  <v-row dense class="mb-4" data-testid="dashboard-summary">
    <v-col v-for="card in cards" :key="card.testId" cols="12" sm="6" lg="4">
      <div class="h-100" :data-testid="card.testId">
        <AdminStatCard :label="card.label" :value="card.value" :caption="card.caption" :icon="card.icon" :color="card.color" class="h-100">
          <v-chip :class="card.rateClass" size="x-small" variant="flat" :title="card.compareLabel" data-testid="dashboard-card-rate">
            {{ card.rateText }}
          </v-chip>
          <span class="text-caption text-medium-emphasis ml-1">{{ card.compareLabel }}</span>
        </AdminStatCard>
      </div>
    </v-col>
  </v-row>
</template>
