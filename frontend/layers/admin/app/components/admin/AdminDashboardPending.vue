<script setup lang="ts">
import { mdiAlertOutline, mdiClipboardTextClockOutline, mdiPackageVariantClosed, mdiStoreClockOutline, mdiTagArrowDownOutline, mdiWalletOutline } from '@mdi/js'
import type { AdminDashboardPending } from '#layers/admin/app/types/admin-dashboard'
import { PENDING_TILES, pendingChipClass, type PendingKey } from '#layers/admin/app/lib/admin-dashboard-view'

/**
 * 처리 대기 6칸(FE-33 4칸 + Track 96-2 FE-54 상품·셀러 승인 대기 2칸). 링크가 있는 칸은 해당 관리자 목록(필터 포함)으로 이동하고,
 * 없는 칸은 카운트만 보인다. 톤은 0건 회색·1건 이상 주의(재고만 빨강)이며 판정은 lib/admin-dashboard-view.ts.
 */
const props = defineProps<{
  pending: AdminDashboardPending | null
}>()

const ICONS: Record<PendingKey, string> = {
  settlementPending: mdiWalletOutline,
  claimRequested: mdiClipboardTextClockOutline,
  deliveryReady: mdiPackageVariantClosed,
  lowStock: mdiAlertOutline,
  productPending: mdiTagArrowDownOutline,
  sellerPending: mdiStoreClockOutline,
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
        class="adm-dashboard-pending"
        :class="{ 'adm-dashboard-pending--link': tile.to }"
        :data-testid="`dashboard-pending-${tile.key}`"
      >
        <v-card>
          <v-card-text class="d-flex align-center justify-space-between flex-wrap ga-2 pa-4">
            <div>
              <p class="text-caption text-medium-emphasis font-weight-medium text-no-wrap mb-1">{{ tile.label }}</p>
              <p class="text-h6 font-weight-bold mb-0" data-testid="dashboard-pending-count">
                {{ pending ? `${countOf(tile.key).toLocaleString('ko-KR')}건` : '—' }}
              </p>
            </div>
            <v-chip :class="pendingChipClass(tile, countOf(tile.key))" size="small" variant="flat" :prepend-icon="ICONS[tile.key]">
              {{ countOf(tile.key) > 0 ? '확인 필요' : '없음' }}
            </v-chip>
          </v-card-text>
        </v-card>
      </component>
    </v-col>
  </v-row>
</template>
