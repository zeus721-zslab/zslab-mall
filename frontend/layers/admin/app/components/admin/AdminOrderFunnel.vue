<script setup lang="ts">
import { mdiInformationOutline } from '@mdi/js'
import type { AdminOrderFunnel } from '#layers/admin/app/types/admin-order-stats'
import { formatRate, funnelStages } from '#layers/admin/app/lib/admin-order-stats-view'
import { formatCount } from '#layers/admin/app/lib/admin-sales-stats-view'

/**
 * 결제 코호트 퍼널(FE-35·D-182). 결제 → 발송 → 배송완료 → 구매확정 4단계를 가로 막대(폭 = 결제 대비 도달률)로 그리고 단계별 건수·도달률·
 * 직전 대비 이탈률을 병기한다. 취소·반품은 단계가 아니라 이탈 사유라 퍼널 옆 칩으로 따로 표기한다. 비율 계산은 lib 순수 함수(BE는 건수만).
 */
const props = defineProps<{
  funnel: AdminOrderFunnel | null
}>()

const COHORT_NOTICE = '기간 내 결제된 주문을 기준으로 이후 단계 도달 여부를 집계합니다.'
const BAR_COLORS = ['#2563EB', '#0EA5E9', '#22C55E', '#A855F7']
const MIN_BAR_WIDTH_PERCENT = 2

const stages = computed(() => funnelStages(props.funnel))
const empty = computed(() => props.funnel === null || props.funnel.paidItems === 0)

function barWidth(reachRate: number): string {
  return `${Math.max(reachRate, MIN_BAR_WIDTH_PERCENT)}%`
}
</script>

<template>
  <v-card class="mb-4" data-testid="order-funnel">
    <v-card-text class="pa-5">
      <div class="d-flex align-center justify-space-between flex-wrap ga-2 mb-1">
        <p class="text-subtitle-1 font-weight-bold mb-0">주문 퍼널</p>
        <div class="d-flex align-center ga-2">
          <v-chip size="small" variant="tonal" color="warning" data-testid="funnel-cancelled">취소 {{ formatCount(funnel?.cancelledItems ?? 0) }}</v-chip>
          <v-chip size="small" variant="tonal" color="error" data-testid="funnel-returned">반품 {{ formatCount(funnel?.returnedItems ?? 0) }}</v-chip>
        </div>
      </div>
      <p class="text-caption text-medium-emphasis mb-4" data-testid="funnel-notice">
        <v-icon :icon="mdiInformationOutline" size="14" class="mr-1" />{{ COHORT_NOTICE }}
      </p>
      <p v-if="empty" class="text-body-2 text-medium-emphasis mb-0" data-testid="funnel-empty">데이터 없음</p>
      <div v-else class="d-flex flex-column ga-3">
        <div v-for="(stage, index) in stages" :key="stage.key" class="adm-funnel-stage" :data-testid="`funnel-stage-${stage.key}`">
          <div class="d-flex align-baseline justify-space-between flex-wrap ga-2 mb-1">
            <span class="text-body-2 font-weight-medium">{{ stage.label }}</span>
            <span class="text-body-2">
              <span class="font-weight-bold" data-testid="funnel-stage-count">{{ formatCount(stage.count) }}</span>
              <span class="text-medium-emphasis ml-2">도달 <span data-testid="funnel-stage-reach">{{ formatRate(stage.reachRate) }}</span></span>
              <span v-if="index > 0" class="text-medium-emphasis ml-2">이탈 <span data-testid="funnel-stage-drop">{{ formatRate(stage.dropRate) }}</span></span>
            </span>
          </div>
          <div class="adm-funnel-track">
            <div class="adm-funnel-bar" :style="{ width: barWidth(stage.reachRate), background: BAR_COLORS[index] }" />
          </div>
        </div>
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.adm-funnel-track {
  height: 14px;
  border-radius: 7px;
  background: #f1f5f9;
  overflow: hidden;
}
.adm-funnel-bar {
  height: 100%;
  border-radius: 7px;
  transition: width 0.2s ease;
}
</style>
