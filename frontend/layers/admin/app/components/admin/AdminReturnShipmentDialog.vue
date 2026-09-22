<script setup lang="ts">
import {
  ADMIN_DELIVERY_CARRIER_OPTIONS,
  ADMIN_ORDER_TRACKING_NO_MAX,
  type AdminDeliveryCarrier,
} from '#layers/admin/app/lib/constants/admin-order'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { validateExchangeShipmentForm } from '#layers/admin/app/lib/admin-claim-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/** 회수 송장 대행 등록 대상 최소 정보(목록 행). */
export interface AdminReturnShipmentTarget {
  claimId: string
  productName: string
}

/**
 * 회수 송장 대행 등록 다이얼로그(Track 101-A). 구매자가 회수 송장을 올리지 않아 회수 확인으로 넘어가지 못하는 클레임을
 * 운영자가 대신 진행시킨다(POST /admin/claims/{id}/return-shipment). 폼 규칙·에러 처리는 교환품 발송 다이얼로그와 같다:
 * 400은 fieldErrors, 422(상태 위반·이미 등록됨)는 warning 후 stale(부모가 다시 읽음).
 */
const props = defineProps<{
  open: boolean
  target: AdminReturnShipmentTarget | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const carrier = ref<AdminDeliveryCarrier | null>(null)
const trackingNo = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 상품명이 사라지지 않도록 마지막 대상을 유지한다(AdminExchangeShipmentDialog 패턴).
const lastTarget = ref<AdminReturnShipmentTarget | null>(null)
watch(() => props.target, (next) => { if (next) lastTarget.value = next })

function reset(): void {
  carrier.value = null
  trackingNo.value = ''
  errors.value = {}
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  if (errors.value[field]) {
    const next = { ...errors.value }
    delete next[field]
    errors.value = next
  }
}

const confirmDisabled = computed(() => submitting.value || carrier.value === null || trackingNo.value.trim() === '')

async function submit(): Promise<void> {
  if (!props.target || submitting.value) return
  const validation = validateExchangeShipmentForm({ carrier: carrier.value, trackingNo: trackingNo.value })
  if (Object.keys(validation).length > 0) {
    errors.value = validation
    return
  }
  submitting.value = true
  try {
    await ordersApi.registerReturnShipment(props.target.claimId, { carrier: carrier.value!, trackingNo: trackingNo.value.trim() })
    toast.info('회수 송장을 대신 등록했습니다. 이어서 회수 확인을 진행하세요.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { trackingNo: toAdminErrorMessage(error) }
    } else if (code === 'CLAIM_STATE_INVALID') {
      // 구매자가 그사이 직접 등록했거나 승인 상태가 바뀐 경우: 안내 후 부모가 최신 데이터를 다시 읽는다.
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
    <v-card data-testid="admin-return-shipment-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">회수 송장 대행 등록</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          <span class="font-weight-medium">{{ lastTarget?.productName }}</span>의 회수 송장을 구매자 대신 등록합니다.
          구매자가 직접 등록한 것과 같은 회수 배송이 만들어지며, 구매자 화면에도 동일하게 보입니다.
        </p>
        <v-select
          :model-value="carrier"
          :items="ADMIN_DELIVERY_CARRIER_OPTIONS"
          label="택배사"
          :error-messages="errors.carrier ? [errors.carrier] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="return-shipment-carrier"
          @update:model-value="(value) => { carrier = value ?? null; clearError('carrier') }"
        />
        <v-text-field
          v-model="trackingNo"
          label="송장번호"
          :maxlength="ADMIN_ORDER_TRACKING_NO_MAX"
          :counter="ADMIN_ORDER_TRACKING_NO_MAX"
          :error-messages="errors.trackingNo ? [errors.trackingNo] : []"
          :disabled="submitting"
          data-testid="return-shipment-tracking-no"
          @update:model-value="clearError('trackingNo')"
          @keyup.enter="submit"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="return-shipment-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="return-shipment-ok" @click="submit">대행 등록</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
