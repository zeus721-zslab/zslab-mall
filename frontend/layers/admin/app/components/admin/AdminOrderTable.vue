<script setup lang="ts">
import { mdiDotsVertical, mdiOpenInNew } from '@mdi/js'
import type { AdminOrderSummary } from '#layers/admin/app/types/admin-order'
import { orderStatusLabel } from '~/lib/constants/order'
import { formatDateTime } from '~/lib/utils/datetime'
import {
  ADMIN_DELIVERY_STATUS_LABEL,
  ADMIN_DELIVERY_STATUS_SEMANTIC,
  ADMIN_ORDER_PAGE_SIZES,
  ADMIN_ORDER_STATUS_SEMANTIC,
  ADMIN_PAYMENT_STATUS_LABEL,
  ADMIN_PAYMENT_STATUS_SEMANTIC,
  paymentMethodLabel,
} from '#layers/admin/app/lib/constants/admin-order'
import { formatWon } from '#layers/admin/app/lib/format'
import { sellerNamesLabel, showDeliveryChip } from '#layers/admin/app/lib/admin-order-view'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'

// 주문 표(FE-27·v-data-table-server·AdminProductTable 패턴). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 액션 메뉴는 BE actions 값으로만 항목을 노출한다: CANCEL은 상세 이동(취소는 상세에서만), PREPARE_SHIPMENT·MARK_DELIVERED는
// 부모가 상세를 선조회해 품목·배송을 고르는 다이얼로그를 연다(목록 행에는 품목·배송 id가 없음·recon C1).
const props = defineProps<{
  items: AdminOrderSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  pendingIds: Set<string>
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [item: AdminOrderSummary]
  prepareShipment: [item: AdminOrderSummary]
  markDelivered: [item: AdminOrderSummary]
}>()

// 9컬럼(FE-27 보강): 1440px에서 가로 스크롤이 없도록 일시·금액·결제·주문/배송을 2줄 셀로 병합한다.
const headers = [
  { title: '주문번호', key: 'orderNo', sortable: false },
  { title: '일시', key: 'dates', sortable: false },
  { title: '주문자', key: 'buyer', sortable: false },
  { title: '셀러', key: 'sellerNames', sortable: false },
  { title: '상품', key: 'productSummary', sortable: false },
  { title: '금액', key: 'amounts', sortable: false, align: 'end' as const },
  { title: '결제', key: 'payment', sortable: false },
  { title: '주문 · 배송', key: 'orderDelivery', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

function isPending(item: AdminOrderSummary): boolean {
  return props.pendingIds.has(item.orderId)
}

function hasRowMenu(item: AdminOrderSummary): boolean {
  return item.actions.includes('PREPARE_SHIPMENT') || item.actions.includes('MARK_DELIVERED')
}
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_ORDER_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="orderId"
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-order-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.orderNo`]="{ item }">
      <a
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        :title="item.orderId"
        data-testid="row-order-no"
        @click.prevent="emit('open', item)"
      >{{ item.orderNo }}</a>
    </template>

    <template #[`item.dates`]="{ item }">
      <div class="text-body-2" data-testid="row-ordered-at">{{ formatDateTime(item.orderedAt) }}</div>
      <div class="text-caption text-medium-emphasis" data-testid="row-paid-at">결제 {{ item.paidAt ? formatDateTime(item.paidAt) : '—' }}</div>
    </template>

    <template #[`item.buyer`]="{ item }">
      <div class="text-body-2">{{ item.buyerName ?? '—' }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.buyerEmail ?? '' }}</div>
    </template>

    <template #[`item.sellerNames`]="{ item }">
      {{ sellerNamesLabel(item.sellerNames) }}
    </template>

    <template #[`item.productSummary`]="{ item }">
      <div class="adm-product-name" :title="item.productSummary">{{ item.productSummary || '—' }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.itemCount }}개 품목</div>
    </template>

    <template #[`item.amounts`]="{ item }">
      <div class="text-body-2 font-weight-medium">{{ formatWon(item.paymentAmount) }}</div>
      <div class="text-caption text-medium-emphasis">배송비 {{ formatWon(item.shippingFee) }}</div>
    </template>

    <template #[`item.payment`]="{ item }">
      <v-chip
        v-if="item.paymentStatus"
        :class="semanticChipClass(ADMIN_PAYMENT_STATUS_SEMANTIC[item.paymentStatus])"
        size="small"
        variant="flat"
        data-testid="payment-status-chip"
      >
        {{ ADMIN_PAYMENT_STATUS_LABEL[item.paymentStatus] }}
      </v-chip>
      <span v-else class="text-medium-emphasis">—</span>
      <div class="text-caption text-medium-emphasis">{{ item.paymentMethod ? paymentMethodLabel(item.paymentMethod) : '—' }}</div>
    </template>

    <template #[`item.orderDelivery`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <v-chip :class="semanticChipClass(ADMIN_ORDER_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="status-chip">
          {{ orderStatusLabel(item.status) }}
        </v-chip>
        <v-chip
          v-if="item.deliveryStatus && showDeliveryChip(item.status, item.deliveryStatus)"
          :class="semanticChipClass(ADMIN_DELIVERY_STATUS_SEMANTIC[item.deliveryStatus])"
          size="small"
          variant="flat"
          data-testid="delivery-status-chip"
        >
          {{ ADMIN_DELIVERY_STATUS_LABEL[item.deliveryStatus] }}
        </v-chip>
        <v-chip v-if="item.claimInProgress" :class="semanticChipClass('warning')" size="small" variant="flat" data-testid="claim-chip">
          클레임 진행중
        </v-chip>
      </div>
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn :icon="mdiOpenInNew" size="small" variant="text" aria-label="상세" data-testid="row-open" @click="emit('open', item)" />
        <v-menu v-if="hasRowMenu(item)">
          <template #activator="{ props: activatorProps }">
            <v-btn
              v-bind="activatorProps"
              :icon="mdiDotsVertical"
              size="small"
              variant="text"
              :disabled="isPending(item)"
              :loading="isPending(item)"
              aria-label="상태 변경"
              data-testid="row-menu"
            />
          </template>
          <v-list density="compact" min-width="180">
            <v-list-subheader>상태 변경</v-list-subheader>
            <v-list-item
              v-if="item.actions.includes('PREPARE_SHIPMENT')"
              title="송장 등록"
              data-testid="row-prepare-shipment"
              @click="emit('prepareShipment', item)"
            />
            <v-list-item
              v-if="item.actions.includes('MARK_DELIVERED')"
              title="배송완료 처리"
              data-testid="row-mark-delivered"
              @click="emit('markDelivered', item)"
            />
          </v-list>
        </v-menu>
      </div>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
