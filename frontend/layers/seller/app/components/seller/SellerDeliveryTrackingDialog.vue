<script setup lang="ts">
import type { SellerDeliverySummary } from '#layers/seller/app/types/seller-delivery'
import {
  SELLER_DELIVERY_CARRIER_OPTIONS,
  SELLER_TRACKING_NO_MAX,
  type SellerDeliveryCarrier,
} from '#layers/seller/app/lib/constants/seller-order'
import { SELLER_DELIVERY_CORRECTION_REASON_MAX } from '#layers/seller/app/lib/constants/seller-delivery'
import { validateTrackingCorrectionForm } from '#layers/seller/app/lib/seller-delivery-view'
import { extractErrorCode, isSellerSuspendedError, mapFieldErrors, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerDeliveries } from '#layers/seller/app/composables/useSellerDeliveries'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

/**
 * 송장 정정 다이얼로그(Track 90-B-3·관리자 AdminDeliveryTrackingDialog 복제·D-191). 잘못 입력한 택배사·송장번호를 바로잡는 경로라 사유(필수·감사 로그)를 받는다.
 * 현재 값을 기본으로 채우고 성공 시 info 토스트 후 done · 422(배송완료 등 상태 경합)·404는 warning 후 stale · 400(송장 형식 등)은 fieldErrors를
 * 필드 오류로 유지(타 배송과 같은 송장번호는 허용·D-227) · **403 SELLER_SUSPENDED는 danger 토스트로 직접 표시**(FE-44 §8) 후 cancel.
 */
const props = defineProps<{
  open: boolean
  item: Pick<SellerDeliverySummary, 'deliveryId' | 'orderNo' | 'productName' | 'carrier' | 'trackingNo'> | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const deliveriesApi = useSellerDeliveries()
const toast = useSellerToast()

const carrier = ref<SellerDeliveryCarrier | null>(null)
const trackingNo = ref('')
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  carrier.value = props.item?.carrier ?? null
  trackingNo.value = props.item?.trackingNo ?? ''
  reason.value = ''
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

const confirmDisabled = computed(() => submitting.value || !carrier.value || trackingNo.value.trim() === '' || reason.value.trim() === '')

async function submit(): Promise<void> {
  if (submitting.value || !props.item || !carrier.value) return
  const validation = validateTrackingCorrectionForm({ carrier: carrier.value, trackingNo: trackingNo.value, reason: reason.value })
  errors.value = validation
  if (Object.keys(validation).length > 0) return
  submitting.value = true
  try {
    await deliveriesApi.correctTracking(props.item.deliveryId, { carrier: carrier.value, trackingNo: trackingNo.value.trim(), reason: reason.value.trim() })
    toast.info('송장 정보를 수정했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (isSellerSuspendedError(error)) {
      toast.danger(toSellerErrorMessage(error))
      emit('cancel')
    } else if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { trackingNo: toSellerErrorMessage(error) }
    } else if (code === 'DELIVERY_INVALID_STATE' || code === 'DELIVERY_NOT_FOUND') {
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
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="seller-tracking-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">송장 정정</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-1">배송중인 배송의 택배사·송장번호를 정정합니다. 배송 상태는 바뀌지 않으며 사유는 감사 이력에 남습니다.</p>
        <p v-if="item" class="text-caption text-medium-emphasis mb-3" data-testid="tracking-target">{{ item.productName ?? '—' }} · 주문번호 {{ item.orderNo ?? '—' }}</p>
        <v-select
          v-model="carrier"
          :items="SELLER_DELIVERY_CARRIER_OPTIONS"
          label="택배사"
          :error-messages="errors.carrier ? [errors.carrier] : []"
          :disabled="submitting"
          data-testid="tracking-carrier"
          @update:model-value="clearError('carrier')"
        />
        <v-text-field
          v-model="trackingNo"
          label="송장번호"
          :maxlength="SELLER_TRACKING_NO_MAX"
          :error-messages="errors.trackingNo ? [errors.trackingNo] : []"
          :disabled="submitting"
          data-testid="tracking-no"
          @update:model-value="clearError('trackingNo')"
        />
        <v-textarea
          v-model="reason"
          label="사유"
          placeholder="예: 택배사 오선택 정정"
          rows="2"
          auto-grow
          :maxlength="SELLER_DELIVERY_CORRECTION_REASON_MAX"
          :counter="SELLER_DELIVERY_CORRECTION_REASON_MAX"
          :error-messages="errors.reason ? [errors.reason] : []"
          :disabled="submitting"
          data-testid="tracking-reason"
          @update:model-value="clearError('reason')"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="tracking-dialog-cancel" @click="emit('cancel')">취소</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="tracking-dialog-ok" @click="submit">
          수정
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
