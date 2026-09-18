<script setup lang="ts">
import { mdiCashMultiple, mdiCashRefund, mdiPercentOutline, mdiWalletOutline } from '@mdi/js'
import type { AdminSettlementMonthlyTotals } from '#layers/admin/app/types/admin-settlement'
import { formatWon } from '#layers/admin/app/lib/format'

/**
 * 월 합계 카드 4장(Track 85 FE). BE totals는 필터(상태·검색어)와 무관한 해당 월 전체이며, 지급액 카드 캡션에 상태별 건수(대기/확정/지급)를 싣는다.
 * 지급액 음수는 카드 값으로는 강조하지 않는다(행 단위 강조는 표에서).
 */
defineProps<{
  totals: AdminSettlementMonthlyTotals | null
}>()

function won(value: number | undefined): string {
  return value === undefined ? '—' : formatWon(value)
}

// AdminStatCard는 caption이 falsy면 줄을 렌더하지 않아 카드 높이가 어긋난다. 캡션 없는 카드도 NBSP로 같은 줄을 차지시켜 네 카드 높이를 맞춘다.
const EMPTY_CAPTION = '\u00A0'

function countsCaption(totals: AdminSettlementMonthlyTotals | null): string {
  return totals ? `대기 ${totals.pendingCount} · 확정 ${totals.confirmedCount} · 지급 ${totals.paidCount}` : EMPTY_CAPTION
}
</script>

<template>
  <v-row dense class="mb-4" data-testid="admin-settlement-totals">
    <v-col cols="12" sm="6" md="3">
      <AdminStatCard label="매출" :value="won(totals?.grossAmount)" :caption="EMPTY_CAPTION" :icon="mdiCashMultiple" color="primary" />
    </v-col>
    <v-col cols="12" sm="6" md="3">
      <AdminStatCard label="수수료" :value="won(totals?.feeAmount)" :caption="EMPTY_CAPTION" :icon="mdiPercentOutline" color="info" />
    </v-col>
    <v-col cols="12" sm="6" md="3">
      <AdminStatCard label="환불" :value="won(totals?.refundAmount)" :caption="EMPTY_CAPTION" :icon="mdiCashRefund" color="warning" />
    </v-col>
    <v-col cols="12" sm="6" md="3">
      <AdminStatCard
        label="지급액"
        :value="won(totals?.netAmount)"
        :caption="countsCaption(totals)"
        :icon="mdiWalletOutline"
        color="success"
      />
    </v-col>
  </v-row>
</template>
