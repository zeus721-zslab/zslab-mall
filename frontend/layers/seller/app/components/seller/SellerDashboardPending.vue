<script setup lang="ts">
import { mdiAlertOutline, mdiClipboardTextClockOutline, mdiPackageVariantClosed, mdiWalletOutline } from '@mdi/js'
import type { SellerDashboardPending } from '#layers/seller/app/types/seller-dashboard'
import { PENDING_TILES, pendingChipClass, type PendingKey } from '#layers/seller/app/lib/seller-dashboard-view'

/**
 * 처리 대기 4칸(Track 90-B-3·관리자 AdminDashboardPending 복제). 링크가 있는 칸(배송 대기 → 품목 목록 status=PAID)만 이동하고 나머지는 카운트 +
 * 힌트 문구만 보인다. 톤은 0건 회색·1건 이상 주의이며 판정은 lib/seller-dashboard-view.ts.
 */
const props = defineProps<{
  pending: SellerDashboardPending | null
}>()

const ICONS: Record<PendingKey, string> = {
  deliveryReady: mdiPackageVariantClosed,
  claimRequested: mdiClipboardTextClockOutline,
  lowStock: mdiAlertOutline,
  settlementPending: mdiWalletOutline,
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
        :is="tile.to ? NuxtLink : 'div'"
        :to="tile.to ?? undefined"
        class="slr-dashboard-pending h-100"
        :class="{ 'slr-dashboard-pending--link': tile.to }"
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
