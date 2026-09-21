<script setup lang="ts">
import { mdiClockOutline, mdiTruckFastOutline } from '@mdi/js'
import { sellerLeadTimeCards, type NormalizedSellerOrderStats, type SellerLeadTimeKey } from '#layers/seller/app/lib/seller-order-stats-view'

/**
 * 셀러 처리 소요시간 카드 2장(Track 90-E-2·관리자 AdminLeadTimeCards 복제): 결제→출고 / 출고→배송완료. 값은 중앙값(D-200), 캡션에 표본 수·평균.
 * 표본 0이면 "데이터 없음". 시간 표기는 공용 formatHours(24h 미만 시간·이상 "N일 M시간").
 */
const props = defineProps<{
  leadTime: NormalizedSellerOrderStats['leadTime'] | null
}>()

const ICONS: Record<SellerLeadTimeKey, string> = {
  paidToShipped: mdiTruckFastOutline,
  shippedToDelivered: mdiClockOutline,
}

const cards = computed(() => sellerLeadTimeCards(props.leadTime))
</script>

<template>
  <v-row dense class="mb-4" data-testid="order-lead-time">
    <v-col v-for="card in cards" :key="card.key" cols="12" md="6">
      <div class="h-100" :data-testid="`lead-time-card-${card.key}`">
        <SellerStatCard :label="`${card.label} 중앙값`" :value="card.median" :caption="card.caption" :icon="ICONS[card.key]" color="info" class="h-100">
          <span class="text-caption text-medium-emphasis">평균 <span class="font-weight-medium" data-testid="lead-time-average">{{ card.average }}</span></span>
        </SellerStatCard>
      </div>
    </v-col>
  </v-row>
</template>
