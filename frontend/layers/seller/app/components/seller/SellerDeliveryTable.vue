<script setup lang="ts">
import { mdiContentCopy, mdiDotsVertical } from '@mdi/js'
import type { SellerDeliverySummary } from '#layers/seller/app/types/seller-delivery'
import { formatDateTime } from '~/lib/utils/datetime'
import { elapsedChip, type ElapsedChip } from '~/lib/utils/elapsed-days'
import {
  SELLER_DELIVERY_CARRIER_LABEL,
  SELLER_DELIVERY_STATUS_LABEL,
  SELLER_DELIVERY_STATUS_SEMANTIC,
} from '#layers/seller/app/lib/constants/seller-order'
import { SELLER_DELIVERY_DIRECTION_LABEL, SELLER_DELIVERY_PAGE_SIZES } from '#layers/seller/app/lib/constants/seller-delivery'
import { canCorrectTracking, canMarkDelivered, deliveryClaimChip, hasRowActions } from '#layers/seller/app/lib/seller-delivery-view'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'

// 배송 표(Track 90-B-3·v-data-table-server·관리자 AdminDeliveryTable 복제). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 주문번호 클릭 → 품목 상세 이동(openOrderItem·orderItemId). 행 액션 메뉴(배송완료·송장 정정)는 SHIPPING 행에만(판정은 lib/seller-delivery-view).
// 상세 다이얼로그는 없다(셀러 배송 상세 API 부재·행에 필요한 정보가 다 있음). 송장번호 복사는 부모가 클립보드·토스트를 처리한다.
const props = defineProps<{
  items: SellerDeliverySummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  pendingIds: Set<string>
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  openOrderItem: [item: SellerDeliverySummary]
  markDelivered: [item: SellerDeliverySummary]
  correctTracking: [item: SellerDeliverySummary]
  copyTracking: [trackingNo: string]
}>()

// 8컬럼: 1280px에서 가로 스크롤이 없도록 택배사는 송장번호 셀 캡션으로, 발송일·배송완료일은 2줄 셀로 병합한다.
const headers = [
  { title: '주문번호', key: 'orderNo', sortable: false },
  { title: '상품', key: 'productName', sortable: false },
  { title: '수령인', key: 'recipientName', sortable: false },
  { title: '구분', key: 'kind', sortable: false },
  { title: '상태', key: 'status', sortable: false },
  { title: '송장번호', key: 'trackingNo', sortable: false },
  { title: '발송 · 완료', key: 'dates', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

/** 경과 N일(C-15): 배송중(SHIPPING) 행만 발송일 기준으로 표시한다. */
function shippingElapsed(item: SellerDeliverySummary): ElapsedChip | null {
  return item.status === 'SHIPPING' ? elapsedChip(item.shippedAt) : null
}

function isPending(item: SellerDeliverySummary): boolean {
  return props.pendingIds.has(item.deliveryId)
}
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_DELIVERY_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="deliveryId"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-delivery-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.orderNo`]="{ item }">
      <a
        v-if="item.orderNo && item.orderItemId"
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        :title="item.orderItemId"
        data-testid="row-order-no"
        @click.prevent="emit('openOrderItem', item)"
      >{{ item.orderNo }}</a>
      <span v-else class="text-medium-emphasis">{{ item.orderNo ?? '—' }}</span>
    </template>

    <template #[`item.productName`]="{ item }">
      <div class="slr-product-name" :title="item.productName" data-testid="row-product-name">{{ item.productName ?? '—' }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.optionLabel ?? '옵션 없음' }} · {{ item.quantity }}개</div>
    </template>

    <template #[`item.recipientName`]="{ item }">
      {{ item.recipientName ?? '—' }}
    </template>

    <template #[`item.kind`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <v-chip v-if="item.direction === 'RETURN'" :class="semanticChipClass('warning')" size="small" variant="flat" data-testid="direction-chip">
          {{ SELLER_DELIVERY_DIRECTION_LABEL.RETURN }}
        </v-chip>
        <v-chip v-if="deliveryClaimChip(item)" :class="semanticChipClass(deliveryClaimChip(item)!.semantic)" size="small" variant="flat" :title="item.claimId" data-testid="claim-chip">
          {{ deliveryClaimChip(item)!.text }}
        </v-chip>
        <span v-if="item.direction === 'OUTBOUND' && !item.claimType" class="text-medium-emphasis text-body-2">원 발송</span>
      </div>
    </template>

    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(SELLER_DELIVERY_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="status-chip">
        {{ SELLER_DELIVERY_STATUS_LABEL[item.status] }}
      </v-chip>
    </template>

    <template #[`item.trackingNo`]="{ item }">
      <div v-if="item.trackingNo" class="d-flex align-center ga-1">
        <span class="text-body-2" data-testid="row-tracking-no">{{ item.trackingNo }}</span>
        <v-btn :icon="mdiContentCopy" size="x-small" variant="text" aria-label="송장번호 복사" data-testid="row-copy-tracking" @click="emit('copyTracking', item.trackingNo!)" />
      </div>
      <span v-else class="text-medium-emphasis">—</span>
      <div class="text-caption text-medium-emphasis" data-testid="row-carrier">{{ SELLER_DELIVERY_CARRIER_LABEL[item.carrier] }}</div>
    </template>

    <template #[`item.dates`]="{ item }">
      <div class="text-body-2" data-testid="row-shipped-at">{{ item.shippedAt ? formatDateTime(item.shippedAt) : '—' }}</div>
      <div class="text-caption text-medium-emphasis" data-testid="row-delivered-at">완료 {{ item.deliveredAt ? formatDateTime(item.deliveredAt) : '—' }}</div>
      <v-chip v-if="shippingElapsed(item)" :class="`slr-chip slr-chip--${shippingElapsed(item)!.tone}`" size="x-small" variant="flat" class="mt-1" data-testid="row-elapsed">
        {{ shippingElapsed(item)!.text }}
      </v-chip>
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end">
        <v-menu v-if="hasRowActions(item)">
          <template #activator="{ props: activatorProps }">
            <v-btn v-bind="activatorProps" :icon="mdiDotsVertical" size="small" variant="text" :disabled="isPending(item)" :loading="isPending(item)" aria-label="배송 처리" data-testid="row-menu" />
          </template>
          <v-list density="compact" min-width="180">
            <v-list-subheader>배송 처리</v-list-subheader>
            <v-list-item v-if="canMarkDelivered(item)" title="배송완료 처리" data-testid="row-mark-delivered" @click="emit('markDelivered', item)" />
            <v-list-item v-if="canCorrectTracking(item.status)" title="송장 정정" data-testid="row-correct-tracking" @click="emit('correctTracking', item)" />
          </v-list>
        </v-menu>
        <span v-else class="text-caption text-medium-emphasis">—</span>
      </div>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
