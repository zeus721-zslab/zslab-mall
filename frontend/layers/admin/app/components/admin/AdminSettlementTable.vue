<script setup lang="ts">
import { mdiAlertCircleOutline, mdiWalletOutline } from '@mdi/js'
import type { AdminSettlementSummary } from '#layers/admin/app/types/admin-settlement'
import { ADMIN_SETTLEMENT_PAGE_SIZES, ADMIN_SETTLEMENT_STATUS_SEMANTIC } from '#layers/admin/app/lib/constants/admin-settlement'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { formatDateOnly, formatSettlementPeriod, isNegativeNet, settlementStatusLabel } from '#layers/admin/app/lib/admin-settlement-view'
import { formatWon } from '#layers/admin/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 정산 행 표(Track 85 FE·읽기 전용·행 액션은 상세로만). 월별 목록(셀러 컬럼)과 셀러별 이력(기간 컬럼)이 같은 BE 행
 * (AdminSettlementSummaryResponse)을 쓰므로 첫 컬럼만 mode로 바꾼다. 상태(로딩·에러·페이지)는 부모가 소유한다.
 */
const props = defineProps<{
  /** monthly: 첫 컬럼 셀러 상호 / seller: 첫 컬럼 정산 기간 */
  mode: 'monthly' | 'seller'
  rows: AdminSettlementSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  loadError: string | null
  emptyTitle: string
  emptyMessage?: string
  /** 필터 걸린 빈 상태에서 "필터 초기화" 버튼 노출 */
  showReset?: boolean
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [row: AdminSettlementSummary]
  retry: []
  reset: []
}>()

const headers = computed(() => [
  props.mode === 'monthly'
    ? { title: '셀러', key: 'seller', sortable: false }
    : { title: '정산 기간', key: 'period', sortable: false },
  { title: '매출', key: 'grossAmount', sortable: false, align: 'end' as const },
  { title: '수수료', key: 'feeAmount', sortable: false, align: 'end' as const },
  { title: '환불', key: 'refundAmount', sortable: false, align: 'end' as const },
  { title: '지급액', key: 'netAmount', sortable: false, align: 'end' as const },
  ...(props.mode === 'monthly' ? [{ title: '판매건수', key: 'saleItemCount', sortable: false, align: 'end' as const }] : []),
  { title: '상태', key: 'status', sortable: false },
  { title: '지급예정일', key: 'scheduledPayDate', sortable: false },
  { title: '지급일', key: 'paidAt', sortable: false },
  ...(props.mode === 'monthly' ? [{ title: '계좌', key: 'bankAccountRegistered', sortable: false }] : []),
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 88 },
])
</script>

<template>
  <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-settlement-error">
    <div class="d-flex align-center justify-space-between flex-wrap ga-2">
      <span>{{ loadError }}</span>
      <v-btn size="small" variant="outlined" color="error" data-testid="admin-settlement-retry" @click="emit('retry')">다시 시도</v-btn>
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
    data-testid="admin-settlement-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.seller`]="{ item }">
      <span class="font-weight-medium" data-testid="row-seller">{{ item.seller.companyName }}</span>
      <div class="text-caption text-medium-emphasis">{{ formatSettlementPeriod(item.periodStart) }}</div>
    </template>
    <template #[`item.period`]="{ item }">
      <span class="font-weight-medium" data-testid="row-period">{{ formatSettlementPeriod(item.periodStart) }}</span>
    </template>
    <template #[`item.grossAmount`]="{ item }">{{ formatWon(item.grossAmount) }}</template>
    <template #[`item.feeAmount`]="{ item }">{{ formatWon(item.feeAmount) }}</template>
    <template #[`item.refundAmount`]="{ item }">{{ formatWon(item.refundAmount) }}</template>
    <template #[`item.netAmount`]="{ item }">
      <span :class="isNegativeNet(item) ? 'text-error font-weight-bold' : 'font-weight-medium'" data-testid="row-net">{{ formatWon(item.netAmount) }}</span>
    </template>
    <template #[`item.saleItemCount`]="{ item }">{{ item.saleItemCount.toLocaleString('ko-KR') }}건</template>
    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(ADMIN_SETTLEMENT_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="row-status">
        {{ settlementStatusLabel(item.status) }}
      </v-chip>
    </template>
    <template #[`item.scheduledPayDate`]="{ item }">{{ formatDateOnly(item.scheduledPayDate) }}</template>
    <template #[`item.paidAt`]="{ item }">
      <span data-testid="row-paid-at">{{ item.paidAt ? formatDateTime(item.paidAt) : '—' }}</span>
    </template>
    <template #[`item.bankAccountRegistered`]="{ item }">
      <v-chip v-if="item.bankAccountRegistered" size="x-small" variant="tonal" color="success" data-testid="row-bank">등록</v-chip>
      <v-chip v-else size="x-small" variant="tonal" color="error" data-testid="row-bank">미등록</v-chip>
    </template>
    <template #[`item.actions`]="{ item }">
      <div class="d-flex justify-end">
        <v-btn size="small" variant="outlined" color="primary" data-testid="row-open" @click="emit('open', item)">상세</v-btn>
      </div>
    </template>

    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-settlement-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiWalletOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-2 font-weight-medium mb-1">{{ emptyTitle }}</p>
        <p v-if="emptyMessage" class="text-body-2 text-medium-emphasis mb-3">{{ emptyMessage }}</p>
        <v-btn v-if="showReset" size="small" variant="outlined" @click="emit('reset')">필터 초기화</v-btn>
      </div>
    </template>
  </v-data-table-server>
</template>
