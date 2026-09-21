<script setup lang="ts">
import { mdiInformationOutline } from '@mdi/js'
import type { SellerOrderFunnel } from '#layers/seller/app/types/seller-order-stats'
import { sellerFunnelStages } from '#layers/seller/app/lib/seller-order-stats-view'
import { formatCount } from '#layers/seller/app/lib/format'
import { formatRate } from '~/lib/stats-view'

/**
 * 셀러 결제 코호트 퍼널(Track 90-E-2·관리자 AdminOrderFunnel 복제·3단계). 결제 → 출고 → 배송완료를 가로 막대(폭 = 결제 대비 도달률)로 그리고
 * 단계별 건수·도달률·직전 대비 이탈률을 병기한다. 내 품목 단위라 혼합 주문의 부분 출고는 품목별로 보인다. 비율 계산은 lib 순수 함수(BE는 건수만).
 */
const props = defineProps<{
  funnel: SellerOrderFunnel | null
}>()

const COHORT_NOTICE = '기간 내 결제된 내 품목을 기준으로 이후 단계 도달 여부를 집계합니다(도달 시각이 기간 밖이어도 포함).'
const BAR_COLORS = ['#0D9488', '#0EA5E9', '#22C55E']
const MIN_BAR_WIDTH_PERCENT = 2

const stages = computed(() => sellerFunnelStages(props.funnel))
const empty = computed(() => props.funnel === null || props.funnel.paidItems === 0)

function barWidth(reachRate: number): string {
  return `${Math.max(reachRate, MIN_BAR_WIDTH_PERCENT)}%`
}
</script>

<template>
  <v-card class="mb-4" data-testid="order-funnel">
    <v-card-text class="pa-5">
      <p class="text-subtitle-1 font-weight-bold mb-1">주문 퍼널</p>
      <p class="text-caption text-medium-emphasis mb-4" data-testid="funnel-notice">
        <v-icon :icon="mdiInformationOutline" size="14" class="mr-1" />{{ COHORT_NOTICE }}
      </p>
      <p v-if="empty" class="text-body-2 text-medium-emphasis mb-0" data-testid="funnel-empty">데이터 없음</p>
      <div v-else class="d-flex flex-column ga-3">
        <div v-for="(stage, index) in stages" :key="stage.key" :data-testid="`funnel-stage-${stage.key}`">
          <div class="d-flex align-baseline justify-space-between flex-wrap ga-2 mb-1">
            <span class="text-body-2 font-weight-medium">{{ stage.label }}</span>
            <span class="text-body-2">
              <span class="font-weight-bold" data-testid="funnel-stage-count">{{ formatCount(stage.count) }}</span>
              <span class="text-medium-emphasis ml-2">도달 <span data-testid="funnel-stage-reach">{{ formatRate(stage.reachRate) }}</span></span>
              <span v-if="index > 0" class="text-medium-emphasis ml-2">이탈 <span data-testid="funnel-stage-drop">{{ formatRate(stage.dropRate) }}</span></span>
            </span>
          </div>
          <div class="slr-funnel-track">
            <div class="slr-funnel-bar" :style="{ width: barWidth(stage.reachRate), background: BAR_COLORS[index] }" />
          </div>
        </div>
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.slr-funnel-track {
  height: 14px;
  border-radius: 7px;
  background: #f1f5f9;
  overflow: hidden;
}
.slr-funnel-bar {
  height: 100%;
  border-radius: 7px;
  transition: width 0.2s ease;
}
</style>
