<script setup lang="ts">
import type { AdminDeliveryDetail } from '#layers/admin/app/types/admin-delivery'
import {
  ADMIN_DELIVERY_CARRIER_OPTIONS,
  ADMIN_ORDER_TRACKING_NO_MAX,
  type AdminDeliveryCarrier,
} from '#layers/admin/app/lib/constants/admin-order'
import { ADMIN_DELIVERY_CORRECTION_REASON_MAX } from '#layers/admin/app/lib/constants/admin-delivery'
import { DELIVERY_TRACKING_NO_FORMAT_MESSAGE, DELIVERY_TRACKING_NO_PATTERN } from '~/lib/constants/delivery'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminDeliveries } from '#layers/admin/app/composables/useAdminDeliveries'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 송장 정정 다이얼로그(FE-37·Track 89-B). 잘못 입력된 택배사·송장번호를 바로잡는 운영 경로라 사유(필수·감사 로그)를 받는다.
 * 현재 값을 기본으로 채우고, 성공 시 info 토스트(값 보정은 중립) 후 done. 422(배송완료 등 상태 경합)는 토스트 후 stale,
 * 400(송장 형식 등)은 fieldErrors 표시(AdminPaymentCancelDialog 패턴). 타 배송과 같은 송장번호는 허용한다(합포장·D-227).
 */
const props = defineProps<{
  open: boolean
  detail: AdminDeliveryDetail | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const deliveriesApi = useAdminDeliveries()
const toast = useAdminToast()

const carrier = ref<AdminDeliveryCarrier | null>(null)
const trackingNo = ref('')
const reason = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  carrier.value = props.detail?.carrier ?? null
  trackingNo.value = props.detail?.trackingNo ?? ''
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
  if (submitting.value || !props.detail || !carrier.value) return
  const nextTrackingNo = trackingNo.value.trim()
  const nextReason = reason.value.trim()
  const localErrors: Record<string, string> = {}
  if (nextTrackingNo === '') localErrors.trackingNo = '송장번호를 입력하세요.'
  else if (!DELIVERY_TRACKING_NO_PATTERN.test(nextTrackingNo)) localErrors.trackingNo = DELIVERY_TRACKING_NO_FORMAT_MESSAGE
  if (nextReason === '') localErrors.reason = '사유를 입력하세요.'
  if (Object.keys(localErrors).length > 0) {
    errors.value = localErrors
    return
  }
  submitting.value = true
  try {
    await deliveriesApi.correctTracking(props.detail.deliveryId, { carrier: carrier.value, trackingNo: nextTrackingNo, reason: nextReason })
    toast.info('송장 정보를 수정했습니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      errors.value = mapFieldErrors(error)
      if (Object.keys(errors.value).length === 0) toast.danger(toAdminErrorMessage(error))
    } else if (code === 'DELIVERY_INVALID_STATE' || code === 'DELIVERY_NOT_FOUND') {
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
  <v-dialog :model-value="open" max-width="520" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-delivery-tracking-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">송장 수정</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          배송중인 배송의 택배사·송장번호를 정정합니다. 배송 상태는 바뀌지 않으며 사유는 감사 이력에 남습니다.
        </p>
        <v-select
          v-model="carrier"
          :items="ADMIN_DELIVERY_CARRIER_OPTIONS"
          label="택배사"
          :error-messages="errors.carrier ? [errors.carrier] : []"
          :disabled="submitting"
          data-testid="tracking-carrier"
          @update:model-value="clearError('carrier')"
        />
        <v-text-field
          v-model="trackingNo"
          label="송장번호"
          :maxlength="ADMIN_ORDER_TRACKING_NO_MAX"
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
          :maxlength="ADMIN_DELIVERY_CORRECTION_REASON_MAX"
          :counter="ADMIN_DELIVERY_CORRECTION_REASON_MAX"
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
