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

/** 교환품 발송 대상 최소 정보(목록 행). */
export interface AdminExchangeShipmentTarget {
  claimId: string
  productName: string
  exchangeOptionLabel: string | null
}

/**
 * 교환품 발송(송장 등록) 다이얼로그(FE-30-3·Track 83 D-177). 검수 합격한 교환 클레임에 택배사·송장번호를 등록하면 BE가 OUTBOUND Delivery를
 * SHIPPING으로 만든다(POST /admin/claims/{id}/register-exchange-shipment). 폼·에러 처리는 검수 다이얼로그의 FAIL 재발송 입력 패턴을 따른다:
 * 400(VALIDATION_FAILED·MALFORMED_REQUEST)은 fieldErrors, 422(검수 전·이미 등록·송장 가드)는 warning 후 stale(부모가 다시 읽음).
 */
const props = defineProps<{
  open: boolean
  target: AdminExchangeShipmentTarget | null
}>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const ordersApi = useAdminOrders()
const toast = useAdminToast()

const carrier = ref<AdminDeliveryCarrier | null>(null)
const trackingNo = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

// 닫힘 애니메이션 동안 상품명이 사라지지 않도록 마지막 대상을 유지한다(target은 즉시 null).
const lastTarget = ref<AdminExchangeShipmentTarget | null>(null)
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
    await ordersApi.registerExchangeShipment(props.target.claimId, { carrier: carrier.value!, trackingNo: trackingNo.value.trim() })
    toast.info('교환품 발송을 등록했습니다. 배송완료 시 품목이 교환 옵션으로 바뀝니다.')
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { trackingNo: toAdminErrorMessage(error) }
    } else if (code === 'CLAIM_STATE_INVALID') {
      // 검수 합격 전·이미 발송 등록됨(송장 가드·Track 80)·경합: 안내 후 부모가 최신 데이터를 다시 읽는다.
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
    <v-card data-testid="admin-exchange-shipment-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">교환품 발송</v-card-title>
      <v-card-text class="px-5">
        <p class="text-body-2 mb-3">
          <span class="font-weight-medium">{{ lastTarget?.productName }}</span>
          <template v-if="lastTarget?.exchangeOptionLabel"> · 교환 옵션 <span class="font-weight-medium" data-testid="exchange-shipment-option">{{ lastTarget.exchangeOptionLabel }}</span></template>
          의 교환품을 발송합니다. 택배사와 송장번호를 등록하면 배송중으로 바뀝니다.
        </p>
        <v-select
          :model-value="carrier"
          :items="ADMIN_DELIVERY_CARRIER_OPTIONS"
          label="택배사"
          :error-messages="errors.carrier ? [errors.carrier] : []"
          :disabled="submitting"
          class="mb-2"
          data-testid="exchange-shipment-carrier"
          @update:model-value="(value) => { carrier = value ?? null; clearError('carrier') }"
        />
        <v-text-field
          v-model="trackingNo"
          label="송장번호"
          :maxlength="ADMIN_ORDER_TRACKING_NO_MAX"
          :counter="ADMIN_ORDER_TRACKING_NO_MAX"
          :error-messages="errors.trackingNo ? [errors.trackingNo] : []"
          :disabled="submitting"
          data-testid="exchange-shipment-tracking-no"
          @update:model-value="clearError('trackingNo')"
          @keyup.enter="submit"
        />
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="exchange-shipment-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="exchange-shipment-ok" @click="submit">발송 등록</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
