<script setup lang="ts">
import type { SellerOrderItemSummary } from '#layers/seller/app/types/seller-order'
import {
  SELLER_DELIVERY_CARRIER_LABEL,
  SELLER_DELIVERY_CARRIER_OPTIONS,
  SELLER_TRACKING_NO_MAX,
  type SellerDeliveryCarrier,
} from '#layers/seller/app/lib/constants/seller-order'
import { itemLabel, validateShipmentForm } from '#layers/seller/app/lib/seller-order-view'
import { extractErrorCode, isSellerSuspendedError, mapFieldErrors, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerOrders } from '#layers/seller/app/composables/useSellerOrders'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 출고(송장 등록) 다이얼로그(Track 90-B-3·관리자 AdminShipmentDialog 복제·품목 1건 고정). 택배사(4값) + 송장번호(≤100).
 * 성공 시 info 토스트 후 done · 422(품목 상태 경합·활성 클레임)·404는 warning 토스트 후 stale(목록 재조회) · 400은 fieldErrors 표시 ·
 * **403 SELLER_SUSPENDED는 여기서 danger 토스트로 직접 표시**(배너는 useSellerApi가 켜지만 호출부가 삼키면 안 된다·FE-44 §8) 후 cancel.
 */
const props = defineProps<{
  open: boolean
  item: Pick<SellerOrderItemSummary, 'orderItemId' | 'orderNo' | 'productName' | 'optionLabel' | 'quantity'> | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useSellerOrders()
const toast = useSellerToast()

const carrier = ref<SellerDeliveryCarrier | null>(null)
const trackingNo = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
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
  if (submitting.value || !props.item) return
  const validation = validateShipmentForm({ carrier: carrier.value, trackingNo: trackingNo.value })
  errors.value = validation
  if (Object.keys(validation).length > 0 || !carrier.value) return

  submitting.value = true
  try {
    const response = await ordersApi.prepareShipment(props.item.orderItemId, { carrier: carrier.value, trackingNo: trackingNo.value.trim() })
    toast.info(`출고 처리했습니다: ${SELLER_DELIVERY_CARRIER_LABEL[response.carrier]} ${response.trackingNo}`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (isSellerSuspendedError(error)) {
      toast.danger(toSellerErrorMessage(error))
      emit('cancel')
    } else if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { trackingNo: toSellerErrorMessage(error) }
    } else if (code === 'ORDER_ITEM_INVALID_STATE' || code === 'ORDER_NOT_FOUND' || code === 'CLAIM_STATE_INVALID' || code === 'DELIVERY_INVALID_STATE') {
      // 조회~처리 사이 품목 상태가 바뀐 경우(다른 창에서 출고·취소 요청 등): 안내 후 목록을 다시 읽는다.
      toast.warning(toSellerErrorMessage(error))
      emit('stale')
    } else {
      toast.danger(toSellerErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="480" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="seller-shipment-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">출고 처리</v-card-title>
      <v-card-text class="px-5">
        <p v-if="item" class="text-body-2 mb-1" data-testid="shipment-item">{{ itemLabel(item) }}</p>
        <p v-if="item" class="text-caption text-medium-emphasis mb-3">주문번호 {{ item.orderNo }} · 송장 등록과 동시에 배송중으로 전환됩니다.</p>
        <v-select
          :model-value="carrier"
          :items="SELLER_DELIVERY_CARRIER_OPTIONS"
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
          :maxlength="SELLER_TRACKING_NO_MAX"
          :counter="SELLER_TRACKING_NO_MAX"
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
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="submitting || !item" data-testid="shipment-dialog-ok" @click="submit">
          출고
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
