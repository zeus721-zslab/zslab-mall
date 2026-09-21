<script setup lang="ts">
import { mdiOpenInNew, mdiPaperclip } from '@mdi/js'
import type { SellerClaimSummary } from '#layers/seller/app/types/seller-claim'
import { claimStatusLabel, claimTypeLabel, refundStatusLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import { elapsedChip, type ElapsedChip } from '~/lib/utils/elapsed-days'
import { SELLER_CLAIM_PAGE_SIZES } from '#layers/seller/app/lib/constants/seller-claim'
import { SELLER_CLAIM_STATUS_SEMANTIC } from '#layers/seller/app/lib/constants/seller-order'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { claimProductLabel, claimReasonLabel } from '#layers/seller/app/lib/seller-claim-view'

// 클레임 표(Track 90-D-1·v-data-table-server·SellerOrderItemTable 복제·조회 전용). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 처리 버튼·액션 컬럼은 없다 — 승인·거부·검수는 관리자 전용이며 셀러는 상세 열람만 한다.
defineProps<{
  items: SellerClaimSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [item: SellerClaimSummary]
}>()

// 6컬럼: 1440px에서 가로 스크롤이 없도록 요청/처리 일시·상품/옵션·상태/환불을 2줄 셀로 병합한다.
/** 경과 N일(C-15): 진행 중(REQUESTED·APPROVED) 행만 요청일 기준으로 표시한다. */
function pendingElapsed(item: SellerClaimSummary): ElapsedChip | null {
  return item.status === 'REQUESTED' || item.status === 'APPROVED' ? elapsedChip(item.requestedAt) : null
}

const headers = [
  { title: '유형', key: 'type', sortable: false },
  { title: '요청 · 처리', key: 'dates', sortable: false },
  { title: '주문번호', key: 'orderNo', sortable: false },
  { title: '상품', key: 'product', sortable: false },
  { title: '사유', key: 'reason', sortable: false },
  { title: '상태', key: 'status', sortable: false },
  { title: '', key: 'open', sortable: false, align: 'end' as const },
]
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_CLAIM_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="claimId"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-claim-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.type`]="{ item }">
      <a
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        :title="item.claimId"
        data-testid="row-claim-type"
        @click.prevent="emit('open', item)"
      >{{ claimTypeLabel(item.type) }}</a>
    </template>

    <template #[`item.dates`]="{ item }">
      <div class="text-body-2" data-testid="row-requested-at">{{ formatDateTime(item.requestedAt) }}</div>
      <div class="text-caption text-medium-emphasis" data-testid="row-processed-at">처리 {{ item.processedAt ? formatDateTime(item.processedAt) : '—' }}</div>
      <v-chip v-if="pendingElapsed(item)" :class="`slr-chip slr-chip--${pendingElapsed(item)!.tone}`" size="x-small" variant="flat" class="mt-1" data-testid="row-elapsed">
        {{ pendingElapsed(item)!.text }}
      </v-chip>
    </template>

    <template #[`item.orderNo`]="{ item }">
      <span data-testid="row-order-no">{{ item.orderNo }}</span>
    </template>

    <template #[`item.product`]="{ item }">
      <div class="slr-product-name" :title="claimProductLabel(item)" data-testid="row-product-name">{{ item.productName }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.optionLabel ?? '옵션 없음' }}</div>
    </template>

    <template #[`item.reason`]="{ item }">
      <div class="text-body-2" data-testid="row-reason">{{ claimReasonLabel(item.reasonCode) }}</div>
      <div v-if="item.attachmentCount > 0" class="text-caption text-medium-emphasis d-flex align-center ga-1" data-testid="row-attachment-count">
        <v-icon :icon="mdiPaperclip" size="14" />첨부 {{ item.attachmentCount }}장
      </div>
    </template>

    <template #[`item.status`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <v-chip :class="semanticChipClass(SELLER_CLAIM_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="status-chip">
          {{ claimStatusLabel(item.status) }}
        </v-chip>
        <v-chip v-if="item.refundStatus" size="small" variant="outlined" data-testid="refund-status-chip">
          {{ refundStatusLabel(item.refundStatus) }}
        </v-chip>
      </div>
    </template>

    <template #[`item.open`]="{ item }">
      <v-btn :icon="mdiOpenInNew" size="small" variant="text" aria-label="상세" data-testid="row-open" @click="emit('open', item)" />
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
