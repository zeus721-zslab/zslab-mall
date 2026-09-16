<script setup lang="ts">
import type { AdminOrderDetail } from '#layers/admin/app/types/admin-order'
import {
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_DELIVERY_CARRIER_OPTIONS,
  ADMIN_ORDER_TRACKING_NO_MAX,
  type AdminDeliveryCarrier,
} from '#layers/admin/app/lib/constants/admin-order'
import { mapFieldErrors, shippableItems, validateShipmentForm } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 송장 등록 다이얼로그(FE-27). 대상 품목(PAID·1개면 자동 선택) + 택배사(4값) + 송장번호(≤100). 성공 시 info 토스트(상태 전환은 중립)
 * 후 done, 422(품목 상태 경합)는 warning 토스트 후 stale, 400은 fieldErrors 표시.
 */
const props = defineProps<{
  open: boolean
  detail: AdminOrderDetail | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const candidates = computed(() => (props.detail ? shippableItems(props.detail.items) : []))
const itemOptions = computed(() => candidates.value.map((item) => ({
  value: item.orderItemId,
  title: `${item.productName}${item.optionLabel ? ` (${item.optionLabel})` : ''} · 수량 ${item.quantity}`,
})))

const orderItemId = ref<string | null>(null)
const carrier = ref<AdminDeliveryCarrier | null>(null)
const trackingNo = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  orderItemId.value = candidates.value.length === 1 ? candidates.value[0]!.orderItemId : null
  carrier.value = null
  trackingNo.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  const validation = validateShipmentForm({ orderItemId: orderItemId.value, carrier: carrier.value, trackingNo: trackingNo.value })
  errors.value = validation
  if (Object.keys(validation).length > 0 || !orderItemId.value || !carrier.value) return

  submitting.value = true
  try {
    const response = await ordersApi.prepareShipment(orderItemId.value, { carrier: carrier.value, trackingNo: trackingNo.value.trim() })
    toast.info(`송장을 등록했습니다: ${ADMIN_DELIVERY_CARRIER_LABEL[response.carrier]} ${response.trackingNo}`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { trackingNo: toAdminErrorMessage(error) }
    } else if (code === 'DELIVERY_INVALID_STATE' || code === 'ORDER_NOT_FOUND') {
      toast.warning(toAdminErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="480" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-shipment-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">송장 등록</v-card-title>
      <v-card-text class="px-5">
        <p v-if="candidates.length === 0" class="text-body-2 text-medium-emphasis mb-3" data-testid="shipment-empty">
          송장을 등록할 수 있는 품목(결제완료)이 없습니다.
        </p>
        <v-select
          :model-value="orderItemId"
          :items="itemOptions"
          label="대상 품목"
          :error-messages="errors.orderItemId ? [errors.orderItemId] : []"
          :disabled="submitting || candidates.length === 0"
          class="mb-2"
          data-testid="shipment-item"
          @update:model-value="(value) => { orderItemId = value ?? null; clearError('orderItemId') }"
        />
        <v-select
          :model-value="carrier"
          :items="ADMIN_DELIVERY_CARRIER_OPTIONS"
          label="택배사"
          :error-messages="errors.carrier ? [errors.carrier] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="shipment-carrier"
          @update:model-value="(value) => { carrier = value ?? null; clearError('carrier') }"
        />
        <v-text-field
          v-model="trackingNo"
          label="송장번호"
          :maxlength="ADMIN_ORDER_TRACKING_NO_MAX"
          :counter="ADMIN_ORDER_TRACKING_NO_MAX"
          :error-messages="errors.trackingNo ? [errors.trackingNo] : []"
          :disabled="submitting"
          data-testid="shipment-tracking-no"
          @update:model-value="clearError('trackingNo')"
          @keyup.enter="submit"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="shipment-dialog-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="submitting || candidates.length === 0" data-testid="shipment-dialog-ok" @click="submit">
          등록
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
