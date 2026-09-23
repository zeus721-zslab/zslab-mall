<script setup lang="ts">
import type { AdminClaimSummary } from '#layers/admin/app/types/admin-claim'
import {
  CLAIM_REASON_LABELS,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  type ClaimReasonCode,
} from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import { elapsedChip, type ElapsedChip } from '~/lib/utils/elapsed-days'
import {
  ADMIN_CLAIM_STATUS_SEMANTIC,
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_ORDER_PAGE_SIZES,
} from '#layers/admin/app/lib/constants/admin-order'
import { formatWon } from '#layers/admin/app/lib/format'
import { inspectionChip, pickupWaitingLabel, refundStatusChip, rowActionAbsenceReason } from '#layers/admin/app/lib/admin-claim-view'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { ADMIN_CLAIM_ACTION_LABEL } from '#layers/admin/app/lib/constants/admin-claim'

// 클레임 표(FE-28·v-data-table-server·AdminOrderTable 패턴). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다.
// 행 액션은 BE availableActions로만 노출한다 — REQUESTED는 APPROVE·REJECT, 반품 승인 후는 CONFIRM_PICKUP·INSPECT(FE-29·Track 81-A).
const props = defineProps<{
  items: AdminClaimSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  pendingIds: Set<string>
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  openOrder: [item: AdminClaimSummary]
  approve: [item: AdminClaimSummary]
  reject: [item: AdminClaimSummary]
  confirmPickup: [item: AdminClaimSummary]
  inspect: [item: AdminClaimSummary]
  registerExchangeShipment: [item: AdminClaimSummary]
  /** 회수 송장 대행 등록(Track 101-A·BE availableActions 무변경·회수 대기 판정으로만 노출). */
  registerReturnShipment: [item: AdminClaimSummary]
  markExchangeDelivered: [item: AdminClaimSummary]
  initiateRefund: [item: AdminClaimSummary]
}>()

// 8컬럼: 1440px에서 가로 스크롤이 없도록 요청/처리 일시·유형/상태·주문/구매자·상품/옵션·요청/거부 사유·환불/회수·검수를 2줄 셀로 병합한다(FE-27 compact 규칙).
/** 경과 N일(C-15): 진행 중(REQUESTED·APPROVED) 행만 요청일 기준으로 표시한다. 종결 행은 방치 대상이 아니다. */
function pendingElapsed(item: AdminClaimSummary): ElapsedChip | null {
  return item.status === 'REQUESTED' || item.status === 'APPROVED' ? elapsedChip(item.requestedAt) : null
}

const headers = [
  { title: '요청 · 처리', key: 'dates', sortable: false },
  { title: '유형 · 상태', key: 'typeStatus', sortable: false },
  { title: '주문 · 구매자', key: 'order', sortable: false },
  { title: '상품', key: 'product', sortable: false },
  { title: '금액', key: 'amount', sortable: false, align: 'end' as const },
  { title: '사유', key: 'reasons', sortable: false },
  { title: '환불 · 회수/검수', key: 'refund', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

function isPending(item: AdminClaimSummary): boolean {
  return props.pendingIds.has(item.claimId)
}

function reasonLabel(code: string): string {
  return CLAIM_REASON_LABELS[code as ClaimReasonCode] ?? code
}

/** 회수/검수 2줄째 caption(FE-29): 검수 chip이 있으면 chip, 아니면 회수 확인 일시 → 회수 송장 → 첨부 수 순으로 짧게. */
function returnCaption(item: AdminClaimSummary): string {
  const parts: string[] = []
  if (item.pickedUpAt) parts.push(`회수 확인 ${formatDateTime(item.pickedUpAt).slice(5)}`)
  else if (item.returnShipment) parts.push(`회수 ${ADMIN_DELIVERY_CARRIER_LABEL[item.returnShipment.carrier]} ${item.returnShipment.trackingNo}`)
  if (item.attachmentCount > 0) parts.push(`첨부 ${item.attachmentCount}`)
  return parts.join(' · ')
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
    item-value="claimId"
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-claim-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.dates`]="{ item }">
      <div class="text-body-2" data-testid="row-requested-at">{{ formatDateTime(item.requestedAt) }}</div>
      <div class="text-caption text-medium-emphasis">처리 {{ item.processedAt ? formatDateTime(item.processedAt) : '—' }}</div>
      <v-chip v-if="pendingElapsed(item)" :class="`adm-chip adm-chip--${pendingElapsed(item)!.tone}`" size="x-small" variant="flat" class="mt-1" data-testid="row-elapsed">
        {{ pendingElapsed(item)!.text }}
      </v-chip>
    </template>

    <template #[`item.typeStatus`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <span class="text-body-2 font-weight-medium" data-testid="row-type">{{ claimTypeLabel(item.type) }}</span>
        <v-chip :class="semanticChipClass(ADMIN_CLAIM_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="row-status-chip">
          {{ claimStatusLabel(item.status) }}
        </v-chip>
      </div>
    </template>

    <template #[`item.order`]="{ item }">
      <a
        v-if="item.orderId && item.orderNo"
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        :title="item.orderId"
        data-testid="row-order-no"
        @click.prevent="emit('openOrder', item)"
      >{{ item.orderNo }}</a>
      <span v-else class="text-medium-emphasis">—</span>
      <div class="text-caption text-medium-emphasis">{{ item.buyerName ?? '—' }}{{ item.buyerEmail ? ` · ${item.buyerEmail}` : '' }}</div>
    </template>

    <template #[`item.product`]="{ item }">
      <div class="adm-product-name" :title="item.productName">{{ item.productName ?? '—' }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.optionLabel ? `${item.optionLabel} · ` : '' }}수량 {{ item.quantity }}</div>
      <div v-if="item.type === 'EXCHANGE' && (item.originalOptionLabel || item.exchangeOptionLabel)" class="text-caption text-medium-emphasis" data-testid="row-exchange-option">
        교환 {{ item.originalOptionLabel ?? '—' }} → {{ item.exchangeOptionLabel ?? '—' }}
      </div>
    </template>

    <template #[`item.amount`]="{ item }">
      <span class="text-body-2 font-weight-medium">{{ item.amount !== undefined ? formatWon(item.amount) : '—' }}</span>
    </template>

    <template #[`item.reasons`]="{ item }">
      <div class="text-body-2" :title="item.reasonDetail">{{ reasonLabel(item.reasonCode) }}</div>
      <div v-if="item.rejectReasonCode" class="text-caption text-error" :title="item.rejectMemo" data-testid="row-reject-reason">
        거부: {{ claimRejectReasonLabel(item.rejectReasonCode) }}
      </div>
    </template>

    <template #[`item.refund`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1">
        <v-chip
          v-if="refundStatusChip(item.refundStatus)"
          :class="semanticChipClass(refundStatusChip(item.refundStatus)!.semantic)"
          size="small"
          variant="flat"
          data-testid="row-refund-chip"
        >
          {{ refundStatusChip(item.refundStatus)!.text }}
        </v-chip>
        <v-chip
          v-if="inspectionChip(item.inspectionResult, item.restock)"
          :class="semanticChipClass(inspectionChip(item.inspectionResult, item.restock)!.semantic)"
          size="small"
          variant="flat"
          data-testid="row-inspection-chip"
        >
          {{ inspectionChip(item.inspectionResult, item.restock)!.text }}
        </v-chip>
        <span v-if="!refundStatusChip(item.refundStatus) && !inspectionChip(item.inspectionResult, item.restock)" class="text-medium-emphasis">—</span>
      </div>
      <div v-if="returnCaption(item)" class="text-caption text-medium-emphasis" :title="item.returnShipment?.trackingNo" data-testid="row-return-caption">
        {{ returnCaption(item) }}
      </div>
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn
          v-if="item.availableActions.includes('APPROVE')"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          :disabled="isPending(item)"
          data-testid="row-approve"
          @click="emit('approve', item)"
        >승인</v-btn>
        <v-btn
          v-if="item.availableActions.includes('REJECT')"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          :disabled="isPending(item)"
          data-testid="row-reject"
          @click="emit('reject', item)"
        >거부</v-btn>
        <v-btn
          v-if="item.availableActions.includes('CONFIRM_PICKUP')"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          :disabled="isPending(item)"
          data-testid="row-confirm-pickup"
          @click="emit('confirmPickup', item)"
        >회수 확인</v-btn>
        <!-- 회수 확인 전(CONFIRM_PICKUP)에도 검수 진입을 열어 다이얼로그에서 회수 확인 → 검수를 한 번에 처리한다(Track 96-1 C-10). -->
        <v-btn
          v-if="item.availableActions.includes('INSPECT') || item.availableActions.includes('CONFIRM_PICKUP')"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          :disabled="isPending(item)"
          data-testid="row-inspect"
          @click="emit('inspect', item)"
        >검수</v-btn>
        <v-btn
          v-if="item.availableActions.includes('REGISTER_EXCHANGE_SHIPMENT')"
          size="x-small"
          color="primary"
          variant="flat"
          :disabled="isPending(item)"
          data-testid="row-register-exchange-shipment"
          @click="emit('registerExchangeShipment', item)"
        >{{ ADMIN_CLAIM_ACTION_LABEL.REGISTER_EXCHANGE_SHIPMENT }}</v-btn>
        <v-btn
          v-if="item.availableActions.includes('MARK_EXCHANGE_DELIVERED')"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          :disabled="isPending(item)"
          data-testid="row-mark-exchange-delivered"
          @click="emit('markExchangeDelivered', item)"
        >{{ ADMIN_CLAIM_ACTION_LABEL.MARK_EXCHANGE_DELIVERED }}</v-btn>
        <v-btn
          v-if="item.availableActions.includes('INITIATE_REFUND')"
          size="x-small"
          variant="flat"
          class="op-risk-action"
          :disabled="isPending(item)"
          data-testid="row-initiate-refund"
          @click="emit('initiateRefund', item)"
        >{{ ADMIN_CLAIM_ACTION_LABEL.INITIATE_REFUND }}</v-btn>
        <!-- Track 101-A: 회수 대기는 "무엇을 기다리는 중"인지 적고, 전화로 받은 송장을 대신 넣을 수 있게 한다.
             BE availableActions에는 값을 더하지 않는다 — 그러면 "필요 액션" 필터·대시보드 처리 대기 타일 집계가 같이 바뀐다. -->
        <template v-if="pickupWaitingLabel(item)">
          <span class="text-caption text-medium-emphasis text-no-wrap" data-testid="row-pickup-waiting">
            {{ pickupWaitingLabel(item) }}
          </span>
          <v-btn
            size="x-small"
            color="primary"
            variant="outlined"
            :disabled="isPending(item)"
            data-testid="row-register-return-shipment"
            @click="emit('registerReturnShipment', item)"
          >회수 송장 대행 등록</v-btn>
        </template>
        <!-- Track 102 FE-64: 액션이 없는 행은 이유를 적는다(종결 2종). 이유를 못 찾으면 기존 "—". -->
        <span
          v-else-if="item.availableActions.length === 0"
          class="text-caption text-medium-emphasis"
          data-testid="row-no-action-reason"
        >{{ rowActionAbsenceReason(item) ?? '—' }}</span>
      </div>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
