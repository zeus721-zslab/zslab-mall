<script setup lang="ts">
import { mdiAlertOutline, mdiClipboardTextClockOutline, mdiPackageVariantClosed, mdiTruckAlertOutline, mdiWalletOutline } from '@mdi/js'
import type { SellerDashboardPending } from '#layers/seller/app/types/seller-dashboard'
import { PENDING_TILES, pendingChipClass, type PendingKey } from '#layers/seller/app/lib/seller-dashboard-view'

/**
 * 처리 대기 5칸(Track 90-B-3·관리자 AdminDashboardPending 복제 + Track 99 FE-61 장기 배송중). 5칸 전부 해당 화면으로 이동한다(Track 96-1 C-14).
 * md에서 4칸 + 1칸으로 접힌다(관리자 8칸이 4+4인 것과 달리 한 칸이 남는 줄이 생긴다). 톤은 0건 회색·1건 이상
 * 주의이며 판정은 lib/seller-dashboard-view.ts.
 */
const props = defineProps<{
  pending: SellerDashboardPending | null
}>()

const ICONS: Record<PendingKey, string> = {
  deliveryReady: mdiPackageVariantClosed,
  claimRequested: mdiClipboardTextClockOutline,
  lowStock: mdiAlertOutline,
  settlementPending: mdiWalletOutline,
  longShipping: mdiTruckAlertOutline,
}

// 문자열 'NuxtLink'는 전역 등록 컴포넌트가 아니라 <nuxtlink> 원소로 렌더된다 → resolveComponent로 실제 컴포넌트를 넘긴다.
const NuxtLink = resolveComponent('NuxtLink')

function countOf(key: PendingKey): number {
  return props.pending ? props.pending[key] : 0
}
</script>

<template>
  <v-row dense class="mb-4" data-testid="dashboard-pending">
    <v-col v-for="tile in PENDING_TILES" :key="tile.key" cols="6" md="3">
      <component
        :is="NuxtLink"
        :to="tile.to"
        class="slr-dashboard-pending slr-dashboard-pending--link h-100"
        :data-testid="`dashboard-pending-${tile.key}`"
      >
        <v-card class="h-100">
          <v-card-text class="pa-4">
            <div class="d-flex align-center justify-space-between flex-wrap ga-2">
              <div>
                <p class="text-caption text-medium-emphasis font-weight-medium text-no-wrap mb-1">{{ tile.label }}</p>
                <p class="text-h6 font-weight-bold mb-0" data-testid="dashboard-pending-count">
                  {{ pending ? `${countOf(tile.key).toLocaleString('ko-KR')}건` : '—' }}
                </p>
              </div>
              <v-chip :class="pendingChipClass(tile, countOf(tile.key))" size="small" variant="flat" :prepend-icon="ICONS[tile.key]">
                {{ countOf(tile.key) > 0 ? '확인 필요' : '없음' }}
              </v-chip>
            </div>
            <p class="text-caption text-medium-emphasis mt-2 mb-0" data-testid="dashboard-pending-hint">{{ tile.hint }}</p>
          </v-card-text>
        </v-card>
      </component>
    </v-col>
  </v-row>
</template>
