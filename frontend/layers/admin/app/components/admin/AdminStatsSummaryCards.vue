<script setup lang="ts">
import type { StatsSummaryCardView } from '#layers/admin/app/lib/admin-order-stats-view'
import { STATS_COMPARE_LABELS, type StatsCompare } from '#layers/admin/app/lib/constants/admin-sales-stats'

/**
 * 통계 요약 카드 공용(FE-35·AdminSalesSummaryCards 패턴 일반화). 카드 뷰(값·비교값·증감 배지 클래스)는 lib 순수 함수가 만들고 여기는 배선만 한다.
 * 비교 없으면 배지 "—"·회색·캡션은 NBSP로 줄을 차지시켜 높이를 맞춘다(FE-33 선례). 아이콘·색은 카드 key별 맵으로 받는다.
 */
const props = defineProps<{
  cards: StatsSummaryCardView[]
  icons: Record<string, string>
  colors: Record<string, 'primary' | 'success' | 'info' | 'warning' | 'error'>
  compare: StatsCompare
  /** data-testid 접두(`${prefix}-card-${key}`·`${prefix}-card-rate`). */
  testidPrefix: string
  /** 열 폭(카드 수에 맞춰 조정·기본 lg=4). */
  lg?: number
}>()

const EMPTY_CAPTION = ' '
const compareLabel = computed(() => (props.compare === 'NONE' ? '비교 없음' : `${STATS_COMPARE_LABELS[props.compare]} 대비`))
</script>

<template>
  <v-row dense class="mb-4" :data-testid="`${testidPrefix}-summary`">
    <v-col v-for="card in cards" :key="card.key" cols="12" sm="6" :lg="lg ?? 4">
      <div class="h-100" :data-testid="`${testidPrefix}-card-${card.key}`">
        <AdminStatCard
          :label="card.label"
          :value="card.value"
          :caption="card.compareValue ? `비교 기간 ${card.compareValue}` : EMPTY_CAPTION"
          :icon="icons[card.key] ?? ''"
          :color="colors[card.key] ?? 'primary'"
          class="h-100"
        >
          <v-chip :class="card.rateClass" size="x-small" variant="flat" :title="compareLabel" :data-testid="`${testidPrefix}-card-rate`">{{ card.rateText }}</v-chip>
          <span class="text-caption text-medium-emphasis ml-1">{{ compareLabel }}</span>
        </AdminStatCard>
      </div>
    </v-col>
  </v-row>
</template>
