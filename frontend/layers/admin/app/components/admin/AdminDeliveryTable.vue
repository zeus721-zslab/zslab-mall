<script setup lang="ts">
import { mdiContentCopy } from '@mdi/js'
import type { AdminDeliverySummary } from '#layers/admin/app/types/admin-delivery'
import { formatDateTime } from '~/lib/utils/datetime'
import { elapsedChip, type ElapsedChip } from '~/lib/utils/elapsed-days'
import {
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_DELIVERY_STATUS_LABEL,
  ADMIN_DELIVERY_STATUS_SEMANTIC,
} from '#layers/admin/app/lib/constants/admin-order'
import { ADMIN_DELIVERY_DIRECTION_LABEL, ADMIN_DELIVERY_PAGE_SIZES } from '#layers/admin/app/lib/constants/admin-delivery'
import { deliveryClaimChip } from '#layers/admin/app/lib/admin-delivery-view'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'

// 배송 표(FE-37·v-data-table-server·AdminOrderTable 패턴). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 행 클릭 → 상세 다이얼로그(open). 주문번호 클릭 → 주문 상세 이동(openOrder). 클레임 배지 클릭 → 클레임 목록(openClaim).
// 송장번호 복사는 부모가 클립보드·토스트를 처리한다(copyTracking).
defineProps<{
  items: AdminDeliverySummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [item: AdminDeliverySummary]
  openOrder: [item: AdminDeliverySummary]
  openClaim: [item: AdminDeliverySummary]
  copyTracking: [trackingNo: string]
}>()

// 8컬럼: 1440px에서 가로 스크롤이 없도록 발송일·배송완료일을 2줄 셀로 병합한다.
/** 경과 N일(C-15): 배송중(SHIPPING) 행만 발송일 기준으로 표시한다. */
function shippingElapsed(item: AdminDeliverySummary): ElapsedChip | null {
  return item.status === 'SHIPPING' ? elapsedChip(item.shippedAt) : null
}

const headers = [
  { title: '주문번호', key: 'orderNo', sortable: false },
  { title: '상품', key: 'productName', sortable: false },
  { title: '수령인', key: 'recipientName', sortable: false },
  { title: '구분', key: 'kind', sortable: false },
  { title: '상태', key: 'status', sortable: false },
  { title: '택배사', key: 'carrier', sortable: false },
  { title: '송장번호', key: 'trackingNo', sortable: false },
  { title: '발송 · 완료', key: 'dates', sortable: false },
]
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_DELIVERY_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="deliveryId"
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-delivery-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
    @click:row="(_event: Event, row: { item: AdminDeliverySummary }) => emit('open', row.item)"
  >
    <template #[`item.orderNo`]="{ item }">
      <a
        v-if="item.orderNo"
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        :title="item.orderId"
        data-testid="row-order-no"
        @click.prevent.stop="emit('openOrder', item)"
      >{{ item.orderNo }}</a>
      <span v-else class="text-medium-emphasis">—</span>
    </template>

    <template #[`item.productName`]="{ item }">
      <div class="adm-product-name" :title="item.productName" data-testid="row-product-name">{{ item.productName ?? '—' }}</div>
    </template>

    <template #[`item.recipientName`]="{ item }">
      {{ item.recipientName ?? '—' }}
    </template>

    <template #[`item.kind`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <v-chip
          v-if="item.direction === 'RETURN'"
          :class="semanticChipClass('warning')"
          size="small"
          variant="flat"
          data-testid="direction-chip"
        >
          {{ ADMIN_DELIVERY_DIRECTION_LABEL.RETURN }}
        </v-chip>
        <v-chip
          v-if="deliveryClaimChip(item)"
          :class="semanticChipClass(deliveryClaimChip(item)!.semantic)"
          size="small"
          variant="flat"
          link
          :title="item.claimId"
          data-testid="claim-chip"
          @click.stop="emit('openClaim', item)"
        >
          {{ deliveryClaimChip(item)!.text }}
        </v-chip>
        <span v-if="item.direction === 'OUTBOUND' && !item.claimType" class="text-medium-emphasis text-body-2">원 발송</span>
      </div>
    </template>

    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(ADMIN_DELIVERY_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="status-chip">
        {{ ADMIN_DELIVERY_STATUS_LABEL[item.status] }}
      </v-chip>
    </template>

    <template #[`item.carrier`]="{ item }">
      {{ ADMIN_DELIVERY_CARRIER_LABEL[item.carrier] }}
    </template>

    <template #[`item.trackingNo`]="{ item }">
      <div v-if="item.trackingNo" class="d-flex align-center ga-1">
        <span class="text-body-2" data-testid="row-tracking-no">{{ item.trackingNo }}</span>
        <v-btn
          :icon="mdiContentCopy"
          size="x-small"
          variant="text"
          aria-label="송장번호 복사"
          data-testid="row-copy-tracking"
          @click.stop="emit('copyTracking', item.trackingNo!)"
        />
      </div>
      <span v-else class="text-medium-emphasis">—</span>
    </template>

    <template #[`item.dates`]="{ item }">
      <div class="text-body-2" data-testid="row-shipped-at">{{ item.shippedAt ? formatDateTime(item.shippedAt) : '—' }}</div>
      <div class="text-caption text-medium-emphasis" data-testid="row-delivered-at">완료 {{ item.deliveredAt ? formatDateTime(item.deliveredAt) : '—' }}</div>
      <v-chip v-if="shippingElapsed(item)" :class="`adm-chip adm-chip--${shippingElapsed(item)!.tone}`" size="x-small" variant="flat" class="mt-1" data-testid="row-elapsed">
        {{ shippingElapsed(item)!.text }}
      </v-chip>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
