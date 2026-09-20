<script setup lang="ts">
import { mdiAlertCircleOutline, mdiWalletOutline } from '@mdi/js'
import type { SellerSettlementSummary } from '#layers/seller/app/types/seller-settlement'
import { SELLER_SETTLEMENT_PAGE_SIZES, SELLER_SETTLEMENT_STATUS_SEMANTIC } from '#layers/seller/app/lib/constants/seller-settlement'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { formatDateOnly, formatSettlementPeriod, isNegativeNet, settlementStatusLabel } from '#layers/seller/app/lib/seller-settlement-view'
import { formatWon } from '#layers/seller/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 셀러 정산 행 표(Track 90-B-3·관리자 AdminSettlementTable(seller 모드) 복제·읽기 전용·행 액션은 상세로만). 첫 컬럼은 정산 기간(본인 정산이라 셀러 컬럼 없음).
 * 상태(로딩·에러·페이지)는 부모가 소유한다.
 */
defineProps<{
  rows: SellerSettlementSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  loadError: string | null
  emptyTitle: string
  emptyMessage?: string
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [row: SellerSettlementSummary]
  retry: []
}>()

const headers = [
  { title: '정산 기간', key: 'period', sortable: false },
  { title: '매출', key: 'grossAmount', sortable: false, align: 'end' as const },
  { title: '수수료', key: 'feeAmount', sortable: false, align: 'end' as const },
  { title: '환불', key: 'refundAmount', sortable: false, align: 'end' as const },
  { title: '지급액', key: 'netAmount', sortable: false, align: 'end' as const },
  { title: '상태', key: 'status', sortable: false },
  { title: '지급예정일', key: 'scheduledPayDate', sortable: false },
  { title: '지급일', key: 'paidAt', sortable: false },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 88 },
]
</script>

<template>
  <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-settlement-error">
    <div class="d-flex align-center justify-space-between flex-wrap ga-2">
      <span>{{ loadError }}</span>
      <v-btn size="small" variant="outlined" color="error" data-testid="seller-settlement-retry" @click="emit('retry')">다시 시도</v-btn>
    </div>
  </v-alert>
  <v-data-table-server
    v-else
    :headers="headers"
    :items="rows"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_SETTLEMENT_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="id"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-settlement-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.period`]="{ item }">
      <span class="font-weight-medium" data-testid="row-period">{{ formatSettlementPeriod(item.periodStart) }}</span>
    </template>
    <template #[`item.grossAmount`]="{ item }">{{ formatWon(item.grossAmount) }}</template>
    <template #[`item.feeAmount`]="{ item }">{{ formatWon(item.feeAmount) }}</template>
    <template #[`item.refundAmount`]="{ item }">{{ formatWon(item.refundAmount) }}</template>
    <template #[`item.netAmount`]="{ item }">
      <span :class="isNegativeNet(item) ? 'text-error font-weight-bold' : 'font-weight-medium'" data-testid="row-net">{{ formatWon(item.netAmount) }}</span>
    </template>
    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(SELLER_SETTLEMENT_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="row-status">
        {{ settlementStatusLabel(item.status) }}
      </v-chip>
    </template>
    <template #[`item.scheduledPayDate`]="{ item }">{{ formatDateOnly(item.scheduledPayDate) }}</template>
    <template #[`item.paidAt`]="{ item }">
      <span data-testid="row-paid-at">{{ item.paidAt ? formatDateTime(item.paidAt) : '—' }}</span>
    </template>
    <template #[`item.actions`]="{ item }">
      <div class="d-flex justify-end">
        <v-btn size="small" variant="outlined" color="primary" data-testid="row-open" @click="emit('open', item)">상세</v-btn>
      </div>
    </template>

    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="seller-settlement-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiWalletOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-2 font-weight-medium mb-1">{{ emptyTitle }}</p>
        <p v-if="emptyMessage" class="text-body-2 text-medium-emphasis mb-0">{{ emptyMessage }}</p>
      </div>
    </template>
  </v-data-table-server>
</template>
