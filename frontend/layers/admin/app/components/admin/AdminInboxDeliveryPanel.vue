<script setup lang="ts">
import type { AdminDeliveryDetail } from '#layers/admin/app/types/admin-delivery'
import { ADMIN_DELIVERY_CARRIER_LABEL } from '#layers/admin/app/lib/constants/admin-order'
import { deliveryCompleteMessage } from '#layers/admin/app/lib/admin-risk-confirm'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminDeliveries } from '#layers/admin/app/composables/useAdminDeliveries'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 인박스 장기 배송중 패널(D-251 · 관리자 · 상세 패널 안). 배송 단건 조회로 송장·발송 시각을 보이고, 배송중이면 확인(반품 기한·자동 구매확정 기점 안내)
 * 후 기존 mark-delivered를 부른다. 조회~처리 사이 상태가 바뀐 경우(422·404)는 안내 후 stale로 페이지가 다시 읽는다.
 */
const props = defineProps<{ deliveryPublicId: string }>()
const emit = defineEmits<{ processed: [outcome: 'done' | 'stale'] }>()

const deliveriesApi = useAdminDeliveries()
const ordersApi = useAdminOrders()
const toast = useAdminToast()

const delivery = ref<AdminDeliveryDetail | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
let loadSequence = 0

async function load(deliveryPublicId: string): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadError.value = null
  delivery.value = null
  try {
    const response = await deliveriesApi.detail(deliveryPublicId)
    if (sequence !== loadSequence) return
    delivery.value = response
  } catch (error) {
    if (sequence !== loadSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const confirmOpen = ref(false)
const busy = ref(false)

async function runComplete(): Promise<void> {
  const current = delivery.value
  if (!current || busy.value) return
  busy.value = true
  try {
    await ordersApi.markDelivered(current.deliveryId)
    toast.success('배송완료로 처리했습니다.')
    confirmOpen.value = false
    emit('processed', 'done')
  } catch (error) {
    confirmOpen.value = false
    const code = extractErrorCode(error)
    if (code === 'DELIVERY_INVALID_STATE' || code === 'DELIVERY_NOT_FOUND') {
      toast.warning(toAdminErrorMessage(error))
      emit('processed', 'stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    busy.value = false
  }
}

// 항목이 바뀌면 열린 다이얼로그를 닫고 새로 읽는다(다이얼로그 상태 선언 뒤에 둔다 — immediate 실행).
watch(() => props.deliveryPublicId, (deliveryPublicId) => {
  confirmOpen.value = false
  void load(deliveryPublicId)
}, { immediate: true })
</script>

<template>
  <div data-testid="inbox-delivery-panel">
    <v-skeleton-loader v-if="loading" type="list-item-three-line" />
    <v-alert v-else-if="loadError" type="error" variant="tonal" density="compact" data-testid="inbox-delivery-error">{{ loadError }}</v-alert>
    <template v-else-if="delivery">
      <v-row dense class="mb-2">
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">송장</div>
          <div class="text-body-2" data-testid="inbox-delivery-tracking">{{ ADMIN_DELIVERY_CARRIER_LABEL[delivery.carrier] }} {{ delivery.trackingNo ?? '-' }}</div>
        </v-col>
        <v-col cols="6">
          <div class="text-caption text-medium-emphasis">발송</div>
          <div class="text-body-2">{{ delivery.shippedAt ? formatDateTime(delivery.shippedAt) : '-' }}</div>
        </v-col>
        <v-col cols="12">
          <div class="text-caption text-medium-emphasis">옵션 · 수량 · 수령인</div>
          <div class="text-body-2">{{ delivery.optionLabel ?? '-' }} · {{ delivery.quantity }}개 · {{ delivery.recipientName ?? '-' }}</div>
        </v-col>
      </v-row>
      <div class="d-flex flex-wrap ga-2 mb-2">
        <v-btn v-if="delivery.status === 'SHIPPING'" color="primary" variant="flat" data-testid="inbox-delivery-complete" @click="confirmOpen = true">
          배송완료 처리
        </v-btn>
      </div>
    </template>

    <AdminConfirmDialog
      :open="confirmOpen"
      title="배송완료 처리"
      :message="delivery ? deliveryCompleteMessage(delivery.productName ?? '', delivery.orderNo ?? null) : ''"
      confirm-label="배송완료"
      :loading="busy"
      risk
      test-id="inbox-delivery-complete-dialog"
      @confirm="runComplete"
      @cancel="confirmOpen = false"
    />
  </div>
</template>
