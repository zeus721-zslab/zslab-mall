<script setup lang="ts">
import { mdiAlertCircleOutline, mdiReceiptTextOutline } from '@mdi/js'
import type { AdminSettlementItem } from '#layers/admin/app/types/admin-settlement'
import { ADMIN_SETTLEMENT_PAGE_SIZES } from '#layers/admin/app/lib/constants/admin-settlement'
import { formatCommissionRate } from '#layers/admin/app/lib/admin-settlement-view'
import { formatWon } from '#layers/admin/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 정산 상세 품목 탭 표(Track 85 FE·읽기 전용). 판매·환불 품목 스냅샷을 같은 컬럼(발생일시·주문·상품(옵션)·수량·금액·수수료율·수수료)으로 보이며
 * 주문번호 클릭은 주문 상세로만 이동한다(orderPublicId·D-134). 상태(로딩·에러·페이지)는 부모가 소유한다.
 */
defineProps<{
  rows: AdminSettlementItem[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  loadError: string | null
  emptyMessage: string
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [row: AdminSettlementItem]
  retry: []
}>()

const headers = [
  { title: '발생일시', key: 'occurredAt', sortable: false },
  { title: '주문번호', key: 'orderPublicId', sortable: false },
  { title: '상품', key: 'productName', sortable: false },
  { title: '수량', key: 'quantity', sortable: false, align: 'end' as const },
  { title: '금액', key: 'amount', sortable: false, align: 'end' as const },
  { title: '수수료율', key: 'commissionRate', sortable: false, align: 'end' as const },
  { title: '수수료', key: 'feeAmount', sortable: false, align: 'end' as const },
]
</script>

<template>
  <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="settlement-items-error">
    <div class="d-flex align-center justify-space-between flex-wrap ga-2">
      <span>{{ loadError }}</span>
      <v-btn size="small" variant="outlined" color="error" data-testid="settlement-items-retry" @click="emit('retry')">다시 시도</v-btn>
    </div>
  </v-alert>
  <v-data-table-server
    v-else
    :headers="headers"
    :items="rows"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_SETTLEMENT_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="id"
    hover
    class="adm-table adm-table--compact"
    data-testid="settlement-items-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.occurredAt`]="{ item }">{{ formatDateTime(item.occurredAt) }}</template>
    <template #[`item.orderPublicId`]="{ item }">
      <a href="#" class="font-weight-medium text-primary text-decoration-none adm-product-id" data-testid="item-order-no" @click.prevent="emit('open', item)">{{ item.orderPublicId }}</a>
    </template>
    <template #[`item.productName`]="{ item }">
      <span data-testid="item-product">{{ item.productName }}</span>
      <div v-if="item.optionLabel" class="text-caption text-medium-emphasis">{{ item.optionLabel }}</div>
    </template>
    <template #[`item.quantity`]="{ item }">{{ item.quantity }}</template>
    <template #[`item.amount`]="{ item }">{{ formatWon(item.amount) }}</template>
    <template #[`item.commissionRate`]="{ item }">
      <span data-testid="item-rate">{{ formatCommissionRate(item.commissionRate) }}</span>
    </template>
    <template #[`item.feeAmount`]="{ item }">
      <span data-testid="item-fee">{{ formatWon(item.feeAmount) }}</span>
    </template>
    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="settlement-items-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiReceiptTextOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-2 font-weight-medium mb-0">{{ emptyMessage }}</p>
      </div>
    </template>
  </v-data-table-server>
</template>
