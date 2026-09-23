<script setup lang="ts">
import { mdiOpenInNew, mdiTruckDeliveryOutline } from '@mdi/js'
import type { SellerOrderItemSummary } from '#layers/seller/app/types/seller-order'
import { orderItemStatusLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import { elapsedChip, type ElapsedChip } from '~/lib/utils/elapsed-days'
import {
  SELLER_CLAIM_STATUS_SEMANTIC,
  SELLER_DELIVERY_CARRIER_LABEL,
  SELLER_DELIVERY_STATUS_LABEL,
  SELLER_DELIVERY_STATUS_SEMANTIC,
  SELLER_ORDER_ITEM_STATUS_SEMANTIC,
  SELLER_ORDER_PAGE_SIZES,
} from '#layers/seller/app/lib/constants/seller-order'
import { canPrepareShipment, claimChipLabel } from '#layers/seller/app/lib/seller-order-view'
import { formatWon } from '#layers/seller/app/lib/format'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'

// 품목 표(Track 90-B-3·v-data-table-server·관리자 AdminOrderTable 복제). 행 = 자기 품목(D-191). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 발송 버튼은 PAID 품목에만 노출(canPrepareShipment)·부모가 다이얼로그를 연다. 배송완료는 배송 화면(진입점 분리·D-191 §5-2).
// 클레임 칩(Track 90-D-1)은 최신 1건 유형·상태(+2건 이상이면 건수)이며 클릭 시 부모가 클레임 상세로 보낸다(조회 전용·처리 없음).
const props = defineProps<{
  items: SellerOrderItemSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  pendingIds: Set<string>
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [item: SellerOrderItemSummary]
  prepareShipment: [item: SellerOrderItemSummary]
  openClaim: [item: SellerOrderItemSummary]
}>()

// 7컬럼: 1440px에서 가로 스크롤이 없도록 주문/결제 일시·상품/옵션·금액/수량을 2줄 셀로 병합한다.
const headers = [
  { title: '주문번호', key: 'orderNo', sortable: false },
  { title: '결제 · 주문', key: 'dates', sortable: false },
  { title: '상품', key: 'product', sortable: false },
  { title: '금액', key: 'amounts', sortable: false, align: 'end' as const },
  { title: '수령인', key: 'recipientName', sortable: false },
  { title: '품목 · 배송', key: 'status', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

/** 경과 N일(C-15): 발송 대기(PAID) 품목만 결제일 기준으로 표시한다. */
function pendingElapsed(item: SellerOrderItemSummary): ElapsedChip | null {
  return item.itemStatus === 'PAID' ? elapsedChip(item.paidAt) : null
}

function isPending(item: SellerOrderItemSummary): boolean {
  return props.pendingIds.has(item.orderItemId)
}
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_ORDER_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="orderItemId"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-order-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.orderNo`]="{ item }">
      <a
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        :title="item.orderItemId"
        data-testid="row-order-no"
        @click.prevent="emit('open', item)"
      >{{ item.orderNo }}</a>
    </template>

    <template #[`item.dates`]="{ item }">
      <div class="text-body-2" data-testid="row-paid-at">{{ item.paidAt ? formatDateTime(item.paidAt) : '—' }}</div>
      <div class="text-caption text-medium-emphasis" data-testid="row-ordered-at">주문 {{ formatDateTime(item.orderedAt) }}</div>
      <v-chip v-if="pendingElapsed(item)" :class="`slr-chip slr-chip--${pendingElapsed(item)!.tone}`" size="x-small" variant="flat" class="mt-1" data-testid="row-elapsed">
        {{ pendingElapsed(item)!.text }}
      </v-chip>
    </template>

    <template #[`item.product`]="{ item }">
      <div class="slr-product-name" :title="item.productName" data-testid="row-product-name">{{ item.productName }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.optionLabel ?? '옵션 없음' }}</div>
    </template>

    <template #[`item.amounts`]="{ item }">
      <div class="text-body-2 font-weight-medium" data-testid="row-total-price">{{ formatWon(item.totalPrice) }}</div>
      <div class="text-caption text-medium-emphasis">{{ formatWon(item.unitPrice) }} × {{ item.quantity }}</div>
    </template>

    <template #[`item.recipientName`]="{ item }">
      {{ item.recipientName ?? '—' }}
    </template>

    <template #[`item.status`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <v-chip :class="semanticChipClass(SELLER_ORDER_ITEM_STATUS_SEMANTIC[item.itemStatus])" size="small" variant="flat" data-testid="status-chip">
          {{ orderItemStatusLabel(item.itemStatus) }}
        </v-chip>
        <v-chip
          v-if="item.delivery"
          :class="semanticChipClass(SELLER_DELIVERY_STATUS_SEMANTIC[item.delivery.status])"
          size="small"
          variant="flat"
          :title="`${SELLER_DELIVERY_CARRIER_LABEL[item.delivery.carrier]} ${item.delivery.trackingNo}`"
          data-testid="delivery-status-chip"
        >
          {{ SELLER_DELIVERY_STATUS_LABEL[item.delivery.status] }}
        </v-chip>
        <v-chip
          v-if="item.claim"
          :class="semanticChipClass(SELLER_CLAIM_STATUS_SEMANTIC[item.claim.status])"
          size="small"
          variant="flat"
          :title="`클레임 상세 보기 (${item.claim.claimId})`"
          data-testid="claim-chip"
          @click.stop="emit('openClaim', item)"
        >
          {{ claimChipLabel(item) }}
        </v-chip>
      </div>
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn :icon="mdiOpenInNew" size="small" variant="text" aria-label="상세" data-testid="row-open" @click="emit('open', item)" />
        <v-btn
          v-if="canPrepareShipment(item)"
          size="small"
          variant="tonal"
          color="primary"
          :prepend-icon="mdiTruckDeliveryOutline"
          :disabled="isPending(item)"
          :loading="isPending(item)"
          data-testid="row-prepare-shipment"
          @click="emit('prepareShipment', item)"
        >
          발송
        </v-btn>
      </div>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
