<script setup lang="ts">
import { mdiClockOutline, mdiTruckFastOutline, mdiClipboardCheckOutline } from '@mdi/js'
import { leadTimeCards, type LeadTimeKey, type NormalizedOrderStats } from '#layers/admin/app/lib/admin-order-stats-view'

/**
 * 처리 소요시간 카드 3장(FE-35): 결제→발송 / 발송→배송완료 / 클레임 요청→종결. 값은 평균, 캡션에 중앙값·표본 수. 표본 0이면 "데이터 없음".
 * 시간 표기는 lib formatHours(24h 미만 시간·이상 "N일 M시간").
 */
const props = defineProps<{
  leadTime: NormalizedOrderStats['leadTime'] | null
}>()

const ICONS: Record<LeadTimeKey, string> = {
  paidToShipped: mdiTruckFastOutline,
  shippedToDelivered: mdiClockOutline,
  claimRequestedToClosed: mdiClipboardCheckOutline,
}

const cards = computed(() => leadTimeCards(props.leadTime))
</script>

<template>
  <v-row dense class="mb-4" data-testid="order-lead-time">
    <v-col v-for="card in cards" :key="card.key" cols="12" md="4">
      <div class="h-100" :data-testid="`lead-time-card-${card.key}`">
        <AdminStatCard :label="card.label" :value="card.average" :caption="card.caption" :icon="ICONS[card.key]" color="info" class="h-100">
          <span class="text-caption text-medium-emphasis">평균 · 중앙값 <span class="font-weight-medium" data-testid="lead-time-median">{{ card.median }}</span></span>
        </AdminStatCard>
      </div>
    </v-col>
  </v-row>
</template>
