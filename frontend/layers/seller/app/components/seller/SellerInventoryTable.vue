<script setup lang="ts">
import { mdiTrayArrowDown, mdiTrayArrowUp } from '@mdi/js'
import type { SellerInventorySummary } from '#layers/seller/app/types/seller-product'
import { formatDateTime } from '~/lib/utils/datetime'
import { SELLER_PRODUCT_PAGE_SIZES } from '#layers/seller/app/lib/constants/seller-product'
import { isOutOfStock } from '#layers/seller/app/lib/seller-product-view'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'

// 재고 표(Track 90-C-3·v-data-table-server·variant 축). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다. 가용 0 이하 행은 danger 칩으로 강조한다.
// 입고·출고 버튼은 부모가 다이얼로그를 연다. 행 단위 처리 중(pendingIds)에는 버튼을 비활성해 중복 클릭을 막는다.
const props = defineProps<{
  items: SellerInventorySummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  pendingIds: Set<string>
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  inbound: [item: SellerInventorySummary]
  outbound: [item: SellerInventorySummary]
}>()

const headers = [
  { title: '상품', key: 'product', sortable: false },
  { title: 'SKU', key: 'sellerSku', sortable: false },
  { title: '보유', key: 'quantityOnHand', sortable: false, align: 'end' as const },
  { title: '예약', key: 'quantityReserved', sortable: false, align: 'end' as const },
  { title: '가용', key: 'quantityAvailable', sortable: false, align: 'end' as const },
  { title: '갱신일', key: 'updatedAt', sortable: false },
  { title: '입출고', key: 'actions', sortable: false, align: 'end' as const },
]

function isPending(item: SellerInventorySummary): boolean {
  return props.pendingIds.has(item.variantPublicId)
}
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_PRODUCT_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="variantPublicId"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-inventory-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.product`]="{ item }">
      <div class="slr-product-name font-weight-medium" :title="item.productName" data-testid="row-product-name">{{ item.productName ?? '—' }}</div>
      <div class="text-caption text-medium-emphasis" data-testid="row-option-label">{{ item.optionLabel ?? '옵션 없음' }}</div>
    </template>

    <template #[`item.sellerSku`]="{ item }">
      <span class="slr-product-id">{{ item.sellerSku ?? '—' }}</span>
    </template>

    <template #[`item.quantityOnHand`]="{ item }">
      <span data-testid="row-on-hand">{{ item.quantityOnHand.toLocaleString('ko-KR') }}</span>
    </template>

    <template #[`item.quantityReserved`]="{ item }">
      {{ item.quantityReserved.toLocaleString('ko-KR') }}
    </template>

    <template #[`item.quantityAvailable`]="{ item }">
      <v-chip
        v-if="isOutOfStock(item)"
        :class="semanticChipClass('danger')"
        size="small"
        variant="flat"
        data-testid="row-available-chip"
      >
        {{ item.quantityAvailable.toLocaleString('ko-KR') }}
      </v-chip>
      <span v-else class="font-weight-medium" data-testid="row-available">{{ item.quantityAvailable.toLocaleString('ko-KR') }}</span>
    </template>

    <template #[`item.updatedAt`]="{ item }">
      <span class="text-body-2">{{ item.updatedAt ? formatDateTime(item.updatedAt) : '—' }}</span>
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn
          size="small"
          variant="tonal"
          color="primary"
          :prepend-icon="mdiTrayArrowDown"
          :disabled="isPending(item)"
          :loading="isPending(item)"
          data-testid="row-inbound"
          @click="emit('inbound', item)"
        >
          입고
        </v-btn>
        <v-btn
          size="small"
          variant="tonal"
          color="warning"
          :prepend-icon="mdiTrayArrowUp"
          :disabled="isPending(item)"
          data-testid="row-outbound"
          @click="emit('outbound', item)"
        >
          출고
        </v-btn>
      </div>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
