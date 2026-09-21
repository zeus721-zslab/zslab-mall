<script setup lang="ts">
import { mdiOpenInNew } from '@mdi/js'
import type { SellerClaimProductRowView } from '#layers/seller/app/lib/seller-order-stats-view'

/**
 * 셀러 클레임 상품별 표(Track 90-E-2·D-200 셀러 전용). BE는 건수 DESC 전량이라 정렬은 두지 않는다. linkable 행(상품 public_id 있음)은 클릭 시
 * emit('open') → 셀러 상품 상세. 비중은 셀러 전체 클레임 대비.
 */
defineProps<{
  rows: SellerClaimProductRowView[]
}>()

const emit = defineEmits<{
  open: [row: SellerClaimProductRowView]
}>()

const SHARE_FRACTION_DIGITS = 2

function onRowClick(row: SellerClaimProductRowView): void {
  if (row.linkable) emit('open', row)
}
</script>

<template>
  <v-card class="h-100" data-testid="claim-by-product">
    <v-card-text class="pa-5">
      <p class="text-subtitle-1 font-weight-bold mb-1">클레임 상품별</p>
      <p class="text-caption text-medium-emphasis mb-3">기간 내 요청된 내 품목 클레임을 상품(주문 시점 상품명)별로 셉니다. 행을 누르면 상품 상세로 이동합니다.</p>
      <v-table density="compact" hover class="slr-table slr-table--compact" data-testid="claim-by-product-table">
        <thead>
          <tr>
            <th class="text-start slr-nowrap">상품</th>
            <th class="text-end slr-nowrap">건수</th>
            <th class="text-end slr-nowrap">비중</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="rows.length === 0">
            <td colspan="3" class="text-center text-medium-emphasis py-4" data-testid="claim-by-product-empty">데이터 없음</td>
          </tr>
          <tr
            v-for="row in rows"
            :key="row.id"
            :class="{ 'slr-row--linkable': row.linkable }"
            :data-testid="row.linkable ? 'claim-product-row-linkable' : 'claim-product-row'"
            @click="onRowClick(row)"
          >
            <td>
              <div class="d-flex align-center ga-1" style="min-width: 0">
                <span class="text-body-2 text-truncate" data-testid="claim-product-name">{{ row.name }}</span>
                <v-icon v-if="row.linkable" :icon="mdiOpenInNew" size="14" class="text-medium-emphasis flex-shrink-0" />
              </div>
            </td>
            <td class="text-end text-body-2 slr-nowrap" data-testid="claim-product-count">{{ row.count.toLocaleString('ko-KR') }}건</td>
            <td class="text-end text-body-2 slr-nowrap">{{ row.share.toFixed(SHARE_FRACTION_DIGITS) }}%</td>
          </tr>
        </tbody>
      </v-table>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.slr-row--linkable {
  cursor: pointer;
}
.slr-nowrap {
  white-space: nowrap;
}
</style>
