<script setup lang="ts">
import type { AdminBuyerSplit } from '#layers/admin/app/types/admin-member-stats'
import { buyerSplitView, formatPeople } from '#layers/admin/app/lib/admin-member-stats-view'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 신규(1회) vs 재구매자 매출 비중(FE-35): 가로 누적 막대(폭 = 매출 비중) + 2분할 인원·매출. 합 0이면 빈 문구.
 */
const props = defineProps<{
  split: AdminBuyerSplit | null
}>()

const SHARE_FRACTION_DIGITS = 1
const COLOR_FIRST = '#0EA5E9'
const COLOR_REPEAT = '#2563EB'

const view = computed(() => buyerSplitView(props.split))
</script>

<template>
  <v-card class="h-100" data-testid="buyer-split">
    <v-card-text class="pa-5">
      <p class="text-subtitle-1 font-weight-bold mb-1">신규 vs 재구매 매출 비중</p>
      <p class="text-caption text-medium-emphasis mb-4">기간 내 1회 결제 구매자와 2회 이상 결제 구매자의 매출을 나눕니다.</p>
      <p v-if="view.empty" class="text-body-2 text-medium-emphasis mb-0" data-testid="buyer-split-empty">데이터 없음</p>
      <template v-else>
        <div class="adm-split-track mb-4" data-testid="buyer-split-bar">
          <div class="adm-split-segment" :style="{ width: `${view.firstTime.revenueShare}%`, background: COLOR_FIRST }" />
          <div class="adm-split-segment" :style="{ width: `${view.repeat.revenueShare}%`, background: COLOR_REPEAT }" />
        </div>
        <v-row dense>
          <v-col cols="6">
            <div class="d-flex align-center ga-2 mb-1">
              <span class="adm-split-dot" :style="{ background: COLOR_FIRST }" />
              <span class="text-body-2 font-weight-medium">신규(1회 구매)</span>
            </div>
            <p class="text-h6 font-weight-bold mb-0" data-testid="buyer-split-first-share">{{ view.firstTime.revenueShare.toFixed(SHARE_FRACTION_DIGITS) }}%</p>
            <p class="text-caption text-medium-emphasis mb-0">{{ formatPeople(view.firstTime.count) }} · {{ formatWon(view.firstTime.revenue) }}</p>
          </v-col>
          <v-col cols="6">
            <div class="d-flex align-center ga-2 mb-1">
              <span class="adm-split-dot" :style="{ background: COLOR_REPEAT }" />
              <span class="text-body-2 font-weight-medium">재구매</span>
            </div>
            <p class="text-h6 font-weight-bold mb-0" data-testid="buyer-split-repeat-share">{{ view.repeat.revenueShare.toFixed(SHARE_FRACTION_DIGITS) }}%</p>
            <p class="text-caption text-medium-emphasis mb-0">{{ formatPeople(view.repeat.count) }} · {{ formatWon(view.repeat.revenue) }}</p>
          </v-col>
        </v-row>
      </template>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.adm-split-track {
  display: flex;
  height: 14px;
  border-radius: 7px;
  background: #f1f5f9;
  overflow: hidden;
}
.adm-split-segment {
  height: 100%;
}
.adm-split-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
</style>
