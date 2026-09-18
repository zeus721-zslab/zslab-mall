<script setup lang="ts">
import { mdiContentCopy } from '@mdi/js'
import type { AdminDeliveryDetail } from '#layers/admin/app/types/admin-delivery'
import { claimTypeLabel, CLAIM_STATUS_LABELS, ORDER_ITEM_STATUS_LABELS } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import {
  ADMIN_CLAIM_STATUS_SEMANTIC,
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_DELIVERY_STATUS_LABEL,
  ADMIN_DELIVERY_STATUS_SEMANTIC,
} from '#layers/admin/app/lib/constants/admin-order'
import { ADMIN_DELIVERY_DIRECTION_LABEL } from '#layers/admin/app/lib/constants/admin-delivery'
import { canCorrectTracking, deliveryClaimChip, trackingCorrectionBlockedReason } from '#layers/admin/app/lib/admin-delivery-view'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'

/**
 * 배송 상세 다이얼로그(FE-37). 배송 정보·배송지 스냅샷·주문/품목·클레임을 읽기 전용으로 보여주고 "송장 수정" 액션만 올린다.
 * 정정 허용 상태(SHIPPING)가 아니면 버튼을 비활성화하고 사유를 툴팁으로 안내한다. 부모가 open·detail·loading을 소유한다.
 */
const props = defineProps<{
  open: boolean
  detail: AdminDeliveryDetail | null
  loading: boolean
}>()
const emit = defineEmits<{ correct: []; openOrder: []; openClaim: []; copyTracking: [trackingNo: string]; close: [] }>()

// 닫힘 애니메이션 동안 내용이 사라지지 않도록 마지막 상세를 유지한다.
const shown = ref<AdminDeliveryDetail | null>(null)
watch(() => props.detail, (next) => { if (next) shown.value = next })

const correctable = computed(() => (shown.value ? canCorrectTracking(shown.value.status) : false))
const blockedReason = computed(() => (shown.value ? trackingCorrectionBlockedReason(shown.value.status) : null))
const claimChip = computed(() => (shown.value ? deliveryClaimChip(shown.value) : null))

function address(detail: AdminDeliveryDetail): string {
  const shipping = detail.shippingAddress
  if (!shipping) return '—'
  return [`(${shipping.zonecode})`, shipping.addressRoad, shipping.addressDetail].filter(Boolean).join(' ')
}
</script>

<template>
  <v-dialog :model-value="open" max-width="640" scrollable @update:model-value="(value: boolean) => !value && emit('close')">
    <v-card data-testid="admin-delivery-detail-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5 d-flex align-center ga-2">
        배송 상세
        <v-chip v-if="shown" :class="semanticChipClass(ADMIN_DELIVERY_STATUS_SEMANTIC[shown.status])" size="small" variant="flat" data-testid="detail-status-chip">
          {{ ADMIN_DELIVERY_STATUS_LABEL[shown.status] }}
        </v-chip>
        <v-chip v-if="claimChip" :class="semanticChipClass(claimChip.semantic)" size="small" variant="flat" link data-testid="detail-claim-chip" @click="emit('openClaim')">
          {{ claimChip.text }}
        </v-chip>
      </v-card-title>
      <v-card-text class="px-5">
        <v-progress-linear v-if="loading && !shown" indeterminate class="my-4" />
        <template v-if="shown">
          <p class="text-overline text-medium-emphasis mb-1">배송</p>
          <v-table density="compact" class="mb-4">
            <tbody>
              <tr><th class="text-medium-emphasis" style="width: 120px">배송 ID</th><td class="text-body-2">{{ shown.deliveryId }}</td></tr>
              <tr><th class="text-medium-emphasis">구분</th><td>{{ ADMIN_DELIVERY_DIRECTION_LABEL[shown.direction] }}{{ shown.claimType ? ` · ${claimChip?.text}` : ' · 원 발송' }}</td></tr>
              <tr><th class="text-medium-emphasis">택배사</th><td data-testid="detail-carrier">{{ ADMIN_DELIVERY_CARRIER_LABEL[shown.carrier] }}</td></tr>
              <tr>
                <th class="text-medium-emphasis">송장번호</th>
                <td>
                  <span data-testid="detail-tracking-no">{{ shown.trackingNo ?? '—' }}</span>
                  <v-btn v-if="shown.trackingNo" :icon="mdiContentCopy" size="x-small" variant="text" aria-label="송장번호 복사" class="ml-1" data-testid="detail-copy-tracking" @click="emit('copyTracking', shown.trackingNo!)" />
                </td>
              </tr>
              <tr><th class="text-medium-emphasis">발송일</th><td>{{ shown.shippedAt ? formatDateTime(shown.shippedAt) : '—' }}</td></tr>
              <tr><th class="text-medium-emphasis">배송완료일</th><td>{{ shown.deliveredAt ? formatDateTime(shown.deliveredAt) : '—' }}</td></tr>
            </tbody>
          </v-table>

          <p class="text-overline text-medium-emphasis mb-1">배송지</p>
          <v-table density="compact" class="mb-4" data-testid="detail-shipping">
            <tbody>
              <tr><th class="text-medium-emphasis" style="width: 120px">수령인</th><td>{{ shown.shippingAddress?.recipientName ?? shown.recipientName ?? '—' }}</td></tr>
              <tr><th class="text-medium-emphasis">연락처</th><td>{{ shown.shippingAddress?.recipientPhone ?? '—' }}</td></tr>
              <tr><th class="text-medium-emphasis">주소</th><td>{{ address(shown) }}</td></tr>
              <tr v-if="shown.shippingAddress?.deliveryMemo"><th class="text-medium-emphasis">배송 메모</th><td>{{ shown.shippingAddress.deliveryMemo }}</td></tr>
            </tbody>
          </v-table>

          <p class="text-overline text-medium-emphasis mb-1">주문 · 품목</p>
          <v-table density="compact" class="mb-4">
            <tbody>
              <tr>
                <th class="text-medium-emphasis" style="width: 120px">주문번호</th>
                <td>
                  <a v-if="shown.orderNo" href="#" class="text-primary text-decoration-none font-weight-medium" data-testid="detail-order-no" @click.prevent="emit('openOrder')">{{ shown.orderNo }}</a>
                  <span v-else>—</span>
                </td>
              </tr>
              <tr><th class="text-medium-emphasis">상품</th><td>{{ shown.productName ?? '—' }}{{ shown.optionLabel ? ` (${shown.optionLabel})` : '' }} · 수량 {{ shown.quantity }}</td></tr>
              <tr><th class="text-medium-emphasis">품목 상태</th><td>{{ shown.orderItemStatus ? ORDER_ITEM_STATUS_LABELS[shown.orderItemStatus] : '—' }}</td></tr>
            </tbody>
          </v-table>

          <template v-if="shown.claimId">
            <p class="text-overline text-medium-emphasis mb-1">클레임</p>
            <v-table density="compact" class="mb-2">
              <tbody>
                <tr><th class="text-medium-emphasis" style="width: 120px">유형</th><td>{{ shown.claimType ? claimTypeLabel(shown.claimType) : '—' }}</td></tr>
                <tr>
                  <th class="text-medium-emphasis">상태</th>
                  <td>
                    <v-chip v-if="shown.claimStatus" :class="semanticChipClass(ADMIN_CLAIM_STATUS_SEMANTIC[shown.claimStatus])" size="small" variant="flat">
                      {{ CLAIM_STATUS_LABELS[shown.claimStatus] }}
                    </v-chip>
                  </td>
                </tr>
                <tr><th class="text-medium-emphasis">클레임 ID</th><td class="text-body-2">{{ shown.claimId }}</td></tr>
              </tbody>
            </v-table>
          </template>
        </template>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" data-testid="delivery-detail-close" @click="emit('close')">닫기</v-btn>
        <!-- 비활성 버튼은 이벤트를 받지 않으므로 툴팁은 감싸는 span에 건다 -->
        <v-tooltip :disabled="correctable" location="top">
          <template #activator="{ props: tooltipProps }">
            <span v-bind="tooltipProps" data-testid="delivery-correct-wrapper">
              <v-btn color="primary" variant="flat" :disabled="!shown || !correctable" data-testid="delivery-correct-tracking" @click="emit('correct')">
                송장 수정
              </v-btn>
            </span>
          </template>
          <span data-testid="delivery-correct-blocked">{{ blockedReason }}</span>
        </v-tooltip>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
