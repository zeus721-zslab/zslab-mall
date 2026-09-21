<script setup lang="ts">
import { mdiOpenInNew } from '@mdi/js'
import type { SellerProductRankRowView } from '#layers/seller/app/lib/seller-product-stats-view'
import { formatWon } from '#layers/seller/app/lib/format'

/**
 * 판매 상위·하위 표(Track 90-E-3). BE 정렬(상위 매출 DESC·하위 역순) 그대로·정렬 UI 없음. linkable 행(상품 public_id 있음)은 클릭 시 emit('open').
 */
defineProps<{
  title: string
  description: string
  rows: SellerProductRankRowView[]
  testid: string
}>()

const emit = defineEmits<{
  open: [row: SellerProductRankRowView]
}>()

function onRowClick(row: SellerProductRankRowView): void {
  if (row.linkable) emit('open', row)
}
</script>

<template>
  <v-card class="h-100" :data-testid="testid">
    <v-card-text class="pa-5">
      <p class="text-subtitle-1 font-weight-bold mb-1">{{ title }}</p>
      <p class="text-caption text-medium-emphasis mb-3">{{ description }}</p>
      <v-table density="compact" hover class="slr-table slr-table--compact" :data-testid="`${testid}-table`">
        <thead>
          <tr>
            <th class="text-start slr-nowrap">#</th>
            <th class="text-start">상품</th>
            <th class="text-end slr-nowrap">매출</th>
            <th class="text-end slr-nowrap">주문수</th>
            <th class="text-end slr-nowrap">수량</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="rows.length === 0">
            <td colspan="5" class="text-center text-medium-emphasis py-4" :data-testid="`${testid}-empty`">데이터 없음</td>
          </tr>
          <tr
            v-for="(row, index) in rows"
            :key="row.id"
            :class="{ 'slr-row--linkable': row.linkable }"
            :data-testid="row.linkable ? `${testid}-row-linkable` : `${testid}-row`"
            @click="onRowClick(row)"
          >
            <td class="text-body-2 text-medium-emphasis">{{ index + 1 }}</td>
            <td>
              <div class="d-flex align-center ga-1" style="min-width: 0">
                <span class="text-body-2 text-truncate" :class="{ 'text-medium-emphasis font-italic': row.deleted }" :data-testid="`${testid}-name`">{{ row.name }}</span>
                <v-icon v-if="row.linkable" :icon="mdiOpenInNew" size="14" class="text-medium-emphasis flex-shrink-0" />
              </div>
            </td>
            <td class="text-end text-body-2 font-weight-medium slr-nowrap" :data-testid="`${testid}-revenue`">{{ formatWon(row.revenue) }}</td>
            <td class="text-end text-body-2 slr-nowrap">{{ row.orderCount.toLocaleString('ko-KR') }}건</td>
            <td class="text-end text-body-2 slr-nowrap">{{ row.quantity.toLocaleString('ko-KR') }}개</td>
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
